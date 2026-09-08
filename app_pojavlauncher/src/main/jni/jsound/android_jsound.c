/*
 * Android JavaSound output backend for KiraziumLauncher.
 *
 * The Android OpenJDK builds intentionally omit the desktop ALSA libjsound.
 * This library implements the DirectAudioDevice JNI contract used by JDK 25
 * and routes signed 16-bit stereo PCM to Android's OpenSL ES buffer queue.
 */

#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <android/log.h>
#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "KiraziumJavaSound"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define DAUDIO_PCM 0
#define OUTPUT_SAMPLE_RATE 44100
#define OUTPUT_CHANNELS 2
#define OUTPUT_SAMPLE_BITS 16
#define OUTPUT_FRAME_SIZE 4
#define DEFAULT_BUFFER_SIZE (OUTPUT_SAMPLE_RATE * OUTPUT_FRAME_SIZE * 2 / 5)

typedef struct AndroidAudioLine {
    SLObjectItf engine_object;
    SLEngineItf engine;
    SLObjectItf output_mix_object;
    SLObjectItf player_object;
    SLPlayItf player;
    SLAndroidSimpleBufferQueueItf queue;

    pthread_mutex_t mutex;
    uint8_t *buffer;
    int buffer_capacity;
    int queued_bytes;
    int64_t played_bytes;
    int64_t position_offset;
    int closing;
} AndroidAudioLine;

static int sl_ok(SLresult result, const char *operation) {
    if (result == SL_RESULT_SUCCESS) return 1;
    LOGE("%s failed: OpenSL ES result=%u", operation, (unsigned int) result);
    return 0;
}

static void buffer_queue_callback(SLAndroidSimpleBufferQueueItf queue, void *context) {
    (void) queue;
    AndroidAudioLine *line = (AndroidAudioLine *) context;
    pthread_mutex_lock(&line->mutex);
    if (!line->closing) {
        line->played_bytes += line->queued_bytes;
        line->queued_bytes = 0;
    }
    pthread_mutex_unlock(&line->mutex);
}

static void destroy_line(AndroidAudioLine *line) {
    if (line == NULL) return;

    pthread_mutex_lock(&line->mutex);
    line->closing = 1;
    pthread_mutex_unlock(&line->mutex);

    if (line->player_object != NULL) {
        if (line->player != NULL) {
            (*line->player)->SetPlayState(line->player, SL_PLAYSTATE_STOPPED);
        }
        if (line->queue != NULL) {
            (*line->queue)->Clear(line->queue);
        }
        (*line->player_object)->Destroy(line->player_object);
    }
    if (line->output_mix_object != NULL) {
        (*line->output_mix_object)->Destroy(line->output_mix_object);
    }
    if (line->engine_object != NULL) {
        (*line->engine_object)->Destroy(line->engine_object);
    }

    /* Destroy prevents new callbacks; this pair waits for one already running. */
    pthread_mutex_lock(&line->mutex);
    pthread_mutex_unlock(&line->mutex);

    free(line->buffer);
    pthread_mutex_destroy(&line->mutex);
    free(line);
}

static AndroidAudioLine *create_line(int buffer_size) {
    AndroidAudioLine *line = calloc(1, sizeof(*line));
    if (line == NULL) return NULL;

    if (pthread_mutex_init(&line->mutex, NULL) != 0) {
        free(line);
        return NULL;
    }

    if (buffer_size <= 0) buffer_size = DEFAULT_BUFFER_SIZE;
    if (buffer_size < OUTPUT_FRAME_SIZE) buffer_size = OUTPUT_FRAME_SIZE;
    buffer_size -= buffer_size % OUTPUT_FRAME_SIZE;
    line->buffer_capacity = buffer_size;
    line->buffer = malloc((size_t) buffer_size);
    if (line->buffer == NULL) {
        destroy_line(line);
        return NULL;
    }

    if (!sl_ok(slCreateEngine(&line->engine_object, 0, NULL, 0, NULL, NULL), "slCreateEngine") ||
        !sl_ok((*line->engine_object)->Realize(line->engine_object, SL_BOOLEAN_FALSE), "engine Realize") ||
        !sl_ok((*line->engine_object)->GetInterface(line->engine_object, SL_IID_ENGINE, &line->engine),
               "engine GetInterface")) {
        destroy_line(line);
        return NULL;
    }

    if (!sl_ok((*line->engine)->CreateOutputMix(line->engine, &line->output_mix_object, 0, NULL, NULL),
               "CreateOutputMix") ||
        !sl_ok((*line->output_mix_object)->Realize(line->output_mix_object, SL_BOOLEAN_FALSE),
               "output mix Realize")) {
        destroy_line(line);
        return NULL;
    }

    SLDataLocator_AndroidSimpleBufferQueue queue_locator = {
        SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, 1
    };
    SLDataFormat_PCM pcm_format = {
        SL_DATAFORMAT_PCM,
        OUTPUT_CHANNELS,
        SL_SAMPLINGRATE_44_1,
        SL_PCMSAMPLEFORMAT_FIXED_16,
        SL_PCMSAMPLEFORMAT_FIXED_16,
        SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT,
        SL_BYTEORDER_LITTLEENDIAN
    };
    SLDataSource source = {&queue_locator, &pcm_format};
    SLDataLocator_OutputMix output_locator = {
        SL_DATALOCATOR_OUTPUTMIX, line->output_mix_object
    };
    SLDataSink sink = {&output_locator, NULL};
    const SLInterfaceID interface_ids[] = {SL_IID_ANDROIDSIMPLEBUFFERQUEUE};
    const SLboolean interface_required[] = {SL_BOOLEAN_TRUE};

    if (!sl_ok((*line->engine)->CreateAudioPlayer(
                   line->engine, &line->player_object, &source, &sink,
                   1, interface_ids, interface_required),
               "CreateAudioPlayer") ||
        !sl_ok((*line->player_object)->Realize(line->player_object, SL_BOOLEAN_FALSE),
               "player Realize") ||
        !sl_ok((*line->player_object)->GetInterface(line->player_object, SL_IID_PLAY, &line->player),
               "player GetInterface(PLAY)") ||
        !sl_ok((*line->player_object)->GetInterface(
                   line->player_object, SL_IID_ANDROIDSIMPLEBUFFERQUEUE, &line->queue),
               "player GetInterface(BUFFERQUEUE)") ||
        !sl_ok((*line->queue)->RegisterCallback(line->queue, buffer_queue_callback, line),
               "buffer queue RegisterCallback")) {
        destroy_line(line);
        return NULL;
    }

    return line;
}

static int16_t scale_sample(int16_t sample, float gain) {
    float scaled = (float) sample * gain;
    if (scaled > 32767.0f) return 32767;
    if (scaled < -32768.0f) return -32768;
    return (int16_t) scaled;
}

JNIEXPORT jboolean JNICALL
Java_com_sun_media_sound_Platform_nIsBigEndian(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_sun_media_sound_DirectAudioDeviceProvider_nGetNumDevices(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return 1;
}

JNIEXPORT jobject JNICALL
Java_com_sun_media_sound_DirectAudioDeviceProvider_nNewDirectAudioDeviceInfo(
    JNIEnv *env, jclass clazz, jint mixer_index) {
    (void) clazz;
    jclass info_class = (*env)->FindClass(
        env, "com/sun/media/sound/DirectAudioDeviceProvider$DirectAudioDeviceInfo");
    if (info_class == NULL) return NULL;

    jmethodID constructor = (*env)->GetMethodID(
        env, info_class, "<init>",
        "(IIILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    if (constructor == NULL) return NULL;

    jstring name = (*env)->NewStringUTF(env, "Android Audio Output");
    jstring vendor = (*env)->NewStringUTF(env, "KiraziumLauncher");
    jstring description = (*env)->NewStringUTF(env, "OpenSL ES");
    jstring version = (*env)->NewStringUTF(env, "1.0");
    if (name == NULL || vendor == NULL || description == NULL || version == NULL) return NULL;

    return (*env)->NewObject(env, info_class, constructor, mixer_index, 0, 1,
                             name, vendor, description, version);
}

JNIEXPORT void JNICALL
Java_com_sun_media_sound_DirectAudioDevice_nGetFormats(
    JNIEnv *env, jclass clazz, jint mixer_index, jint device_id,
    jboolean is_source, jobject formats) {
    (void) mixer_index;
    (void) device_id;
    if (!is_source) return;

    jmethodID add_format = (*env)->GetStaticMethodID(
        env, clazz, "addFormat", "(Ljava/util/Vector;IIIFIZZ)V");
    if (add_format == NULL) return;

    (*env)->CallStaticVoidMethod(env, clazz, add_format, formats,
                                 OUTPUT_SAMPLE_BITS, OUTPUT_FRAME_SIZE,
                                 OUTPUT_CHANNELS, (jfloat) OUTPUT_SAMPLE_RATE,
                                 DAUDIO_PCM, JNI_TRUE, JNI_FALSE);
}

JNIEXPORT jlong JNICALL
Java_com_sun_media_sound_DirectAudioDevice_nOpen(
    JNIEnv *env, jclass clazz, jint mixer_index, jint device_id,
    jboolean is_source, jint encoding, jfloat sample_rate,
    jint sample_size_bits, jint frame_size, jint channels,
    jboolean is_signed, jboolean is_big_endian, jint buffer_size) {
    (void) env;
    (void) clazz;
    (void) mixer_index;
    (void) device_id;

    if (!is_source || encoding != DAUDIO_PCM ||
        (int) sample_rate != OUTPUT_SAMPLE_RATE ||
        sample_size_bits != OUTPUT_SAMPLE_BITS || frame_size != OUTPUT_FRAME_SIZE ||
        channels != OUTPUT_CHANNELS || !is_signed || is_big_endian) {
        return 0;
    }

    return (jlong) (intptr_t) create_line(buffer_size);
}

JNIEXPORT void JNICALL
Java_com_sun_media_sound_DirectAudioDevice_nStart(
    JNIEnv *env, jclass clazz, jlong id, jboolean is_source) {
    (void) env;
    (void) clazz;
    AndroidAudioLine *line = (AndroidAudioLine *) (intptr_t) id;
    if (line != NULL && is_source) {
        sl_ok((*line->player)->SetPlayState(line->player, SL_PLAYSTATE_PLAYING), "SetPlayState(PLAYING)");
    }
}

JNIEXPORT void JNICALL
Java_com_sun_media_sound_DirectAudioDevice_nStop(
    JNIEnv *env, jclass clazz, jlong id, jboolean is_source) {
    (void) env;
    (void) clazz;
    AndroidAudioLine *line = (AndroidAudioLine *) (intptr_t) id;
    if (line != NULL && is_source) {
        sl_ok((*line->player)->SetPlayState(line->player, SL_PLAYSTATE_PAUSED), "SetPlayState(PAUSED)");
    }
}

JNIEXPORT void JNICALL
Java_com_sun_media_sound_DirectAudioDevice_nClose(
    JNIEnv *env, jclass clazz, jlong id, jboolean is_source) {
    (void) env;
    (void) clazz;
    (void) is_source;
    destroy_line((AndroidAudioLine *) (intptr_t) id);
}

JNIEXPORT jint JNICALL
Java_com_sun_media_sound_DirectAudioDevice_nWrite(
    JNIEnv *env, jclass clazz, jlong id, jbyteArray bytes,
    jint offset, jint length, jint conversion_size,
    jfloat left_gain, jfloat right_gain) {
    (void) clazz;
    AndroidAudioLine *line = (AndroidAudioLine *) (intptr_t) id;
    if (line == NULL || bytes == NULL || conversion_size != 0 || offset < 0 || length < 0) return -1;
    if (length == 0) return 0;

    jsize array_length = (*env)->GetArrayLength(env, bytes);
    if (offset > array_length || length > array_length - offset) return -1;

    pthread_mutex_lock(&line->mutex);
    if (line->closing) {
        pthread_mutex_unlock(&line->mutex);
        return -1;
    }
    if (line->queued_bytes != 0) {
        pthread_mutex_unlock(&line->mutex);
        return 0;
    }

    int accepted = length;
    if (accepted > line->buffer_capacity) accepted = line->buffer_capacity;
    accepted -= accepted % OUTPUT_FRAME_SIZE;
    if (accepted == 0) {
        pthread_mutex_unlock(&line->mutex);
        return 0;
    }

    (*env)->GetByteArrayRegion(env, bytes, offset, accepted, (jbyte *) line->buffer);
    if ((*env)->ExceptionCheck(env)) {
        pthread_mutex_unlock(&line->mutex);
        return -1;
    }

    if (left_gain != 1.0f || right_gain != 1.0f) {
        int16_t *samples = (int16_t *) line->buffer;
        int sample_count = accepted / (int) sizeof(int16_t);
        for (int i = 0; i + 1 < sample_count; i += 2) {
            samples[i] = scale_sample(samples[i], left_gain);
            samples[i + 1] = scale_sample(samples[i + 1], right_gain);
        }
    }

    line->queued_bytes = accepted;
    SLresult result = (*line->queue)->Enqueue(line->queue, line->buffer, (SLuint32) accepted);
    if (result != SL_RESULT_SUCCESS) {
        line->queued_bytes = 0;
        LOGE("buffer queue Enqueue failed: OpenSL ES result=%u", (unsigned int) result);
        pthread_mutex_unlock(&line->mutex);
        return -1;
    }
    pthread_mutex_unlock(&line->mutex);
    return accepted;
}

JNIEXPORT jint JNICALL
Java_com_sun_media_sound_DirectAudioDevice_nRead(
    JNIEnv *env, jclass clazz, jlong id, jbyteArray bytes,
    jint offset, jint length, jint conversion_size) {
    (void) env; (void) clazz; (void) id; (void) bytes;
    (void) offset; (void) length; (void) conversion_size;
    return -1;
}

JNIEXPORT jint JNICALL
Java_com_sun_media_sound_DirectAudioDevice_nGetBufferSize(
    JNIEnv *env, jclass clazz, jlong id, jboolean is_source) {
    (void) env; (void) clazz;
    AndroidAudioLine *line = (AndroidAudioLine *) (intptr_t) id;
    return line != NULL && is_source ? line->buffer_capacity : -1;
}

JNIEXPORT jboolean JNICALL
Java_com_sun_media_sound_DirectAudioDevice_nIsStillDraining(
    JNIEnv *env, jclass clazz, jlong id, jboolean is_source) {
    (void) env; (void) clazz;
    AndroidAudioLine *line = (AndroidAudioLine *) (intptr_t) id;
    if (line == NULL || !is_source) return JNI_FALSE;
    pthread_mutex_lock(&line->mutex);
    jboolean draining = line->queued_bytes != 0 ? JNI_TRUE : JNI_FALSE;
    pthread_mutex_unlock(&line->mutex);
    return draining;
}

JNIEXPORT void JNICALL
Java_com_sun_media_sound_DirectAudioDevice_nFlush(
    JNIEnv *env, jclass clazz, jlong id, jboolean is_source) {
    (void) env; (void) clazz;
    AndroidAudioLine *line = (AndroidAudioLine *) (intptr_t) id;
    if (line == NULL || !is_source) return;
    pthread_mutex_lock(&line->mutex);
    (*line->queue)->Clear(line->queue);
    line->queued_bytes = 0;
    pthread_mutex_unlock(&line->mutex);
}

JNIEXPORT jint JNICALL
Java_com_sun_media_sound_DirectAudioDevice_nAvailable(
    JNIEnv *env, jclass clazz, jlong id, jboolean is_source) {
    (void) env; (void) clazz;
    AndroidAudioLine *line = (AndroidAudioLine *) (intptr_t) id;
    if (line == NULL || !is_source) return -1;
    pthread_mutex_lock(&line->mutex);
    jint available = line->queued_bytes == 0 ? line->buffer_capacity : 0;
    pthread_mutex_unlock(&line->mutex);
    return available;
}

JNIEXPORT jlong JNICALL
Java_com_sun_media_sound_DirectAudioDevice_nGetBytePosition(
    JNIEnv *env, jclass clazz, jlong id, jboolean is_source, jlong java_position) {
    (void) env; (void) clazz;
    AndroidAudioLine *line = (AndroidAudioLine *) (intptr_t) id;
    if (line == NULL || !is_source) return java_position;
    pthread_mutex_lock(&line->mutex);
    jlong position = (jlong) (line->position_offset + line->played_bytes);
    pthread_mutex_unlock(&line->mutex);
    return position;
}

JNIEXPORT void JNICALL
Java_com_sun_media_sound_DirectAudioDevice_nSetBytePosition(
    JNIEnv *env, jclass clazz, jlong id, jboolean is_source, jlong position) {
    (void) env; (void) clazz;
    AndroidAudioLine *line = (AndroidAudioLine *) (intptr_t) id;
    if (line == NULL || !is_source) return;
    pthread_mutex_lock(&line->mutex);
    line->position_offset = (int64_t) position - line->played_bytes;
    pthread_mutex_unlock(&line->mutex);
}

JNIEXPORT jboolean JNICALL
Java_com_sun_media_sound_DirectAudioDevice_nRequiresServicing(
    JNIEnv *env, jclass clazz, jlong id, jboolean is_source) {
    (void) env; (void) clazz; (void) id; (void) is_source;
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_sun_media_sound_DirectAudioDevice_nService(
    JNIEnv *env, jclass clazz, jlong id, jboolean is_source) {
    (void) env; (void) clazz; (void) id; (void) is_source;
}

/* Port mixers are not exposed; volume is applied per SourceDataLine in nWrite. */
JNIEXPORT jint JNICALL
Java_com_sun_media_sound_PortMixerProvider_nGetNumDevices(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return 0;
}

JNIEXPORT jobject JNICALL
Java_com_sun_media_sound_PortMixerProvider_nNewPortMixerInfo(
    JNIEnv *env, jclass clazz, jint mixer_index) {
    (void) env; (void) clazz; (void) mixer_index;
    return NULL;
}
