#include <jni.h>
#include <android/log.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#define TAG "KiraziumArtVm"

static JavaVM *g_android_art_vm = NULL;

static void publish_android_art_vm(JavaVM *vm) {
    if (vm == NULL) return;
    g_android_art_vm = vm;

    char value[2 + sizeof(uintptr_t) * 2 + 1];
    snprintf(value, sizeof(value), "0x%" PRIxPTR, (uintptr_t) vm);
    setenv("KIRAZIUM_ANDROID_ART_VM", value, 1);
    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "Published Android ART JavaVM for DreamDisplays MediaCodec: %s", value);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    publish_android_art_vm(vm);
    return JNI_VERSION_1_6;
}

__attribute__((visibility("default")))
JavaVM *kirazium_get_android_art_vm(void) {
    return g_android_art_vm;
}
