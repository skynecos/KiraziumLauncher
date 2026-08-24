package net.kdt.pojavlaunch.customcontrols;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.NonNull;

import org.apache.commons.io.IOUtils;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class LayoutBitmaps {
    private static final Random mKeyPicker = new Random(System.nanoTime());

    private static final int MAX_RAW_JSON_BYTES = 4 * 1024 * 1024;
    private static final int MAX_LAYOUT_JSON_BYTES = 2 * 1024 * 1024;
    private static final int MAX_BITMAP_FILE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 64;
    private static final int MAX_BITMAPS = 48;
    private static final int MAX_BITMAP_DIMENSION = 2048;
    private static final long MAX_TOTAL_EXPANDED_BYTES = 24L * 1024L * 1024L;
    private static final long MAX_TOTAL_BITMAP_PIXELS = 16L * 1024L * 1024L;

    private final Map<String, Bitmap> mBitmaps;

    private LayoutBitmaps() {
        mBitmaps = new HashMap<>();
    }

    private String pickKey() {
        String key;
        do {
            key = Integer.toString(mKeyPicker.nextInt());
        } while (mBitmaps.containsKey(key));
        return key;
    }

    public Bitmap getBitmap(String key) {
        return mBitmaps.get(key);
    }

    public String putBitmap(Bitmap bitmap, String oldKey) {
        String newKey = pickKey();
        mBitmaps.remove(oldKey);
        if(bitmap != null) mBitmaps.put(newKey, bitmap);
        return newKey;
    }

    public static LayoutBitmaps createEmpty() {
        return new LayoutBitmaps();
    }

    private static ControlsContainer createEmpty(String controlsJson) {
        return new ControlsContainer(controlsJson, new LayoutBitmaps());
    }

    private static byte[] readEntryLimited(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) throw new IOException("Control ZIP entry exceeds safety limit");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static Bitmap decodeBitmapSafely(byte[] data, long[] totalPixels) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                || bounds.outWidth > MAX_BITMAP_DIMENSION
                || bounds.outHeight > MAX_BITMAP_DIMENSION) {
            throw new IOException("Control bitmap dimensions exceed safety limit");
        }

        long pixels = (long) bounds.outWidth * (long) bounds.outHeight;
        if (pixels <= 0L || totalPixels[0] + pixels > MAX_TOTAL_BITMAP_PIXELS) {
            throw new IOException("Control bitmap pixel budget exceeded");
        }

        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (bitmap == null) throw new IOException("Invalid control bitmap");
        totalPixels[0] += pixels;
        return bitmap;
    }

    private static ControlsContainer loadFromZip(ZipInputStream zipIn) throws IOException {
        LayoutBitmaps layoutBitmaps = new LayoutBitmaps();
        String layoutContent = null;
        int entryCount = 0;
        int bitmapCount = 0;
        long expandedBytes = 0L;
        long[] totalPixels = new long[]{0L};

        for(ZipEntry entry = zipIn.getNextEntry(); entry != null; entry = zipIn.getNextEntry()) {
            entryCount++;
            if (entryCount > MAX_ZIP_ENTRIES) {
                throw new ZipException("Too many entries in control ZIP");
            }
            if(entry.isDirectory()) {
                zipIn.closeEntry();
                continue;
            }

            String entryName = entry.getName();
            if(entryName == null || entryName.isEmpty()) {
                throw new ZipException("Unnamed control ZIP entry");
            }

            if(entryName.equals("layout.json")) {
                if (layoutContent != null) throw new ZipException("Duplicate layout.json");
                byte[] jsonBytes = readEntryLimited(zipIn, MAX_LAYOUT_JSON_BYTES);
                expandedBytes += jsonBytes.length;
                if (expandedBytes > MAX_TOTAL_EXPANDED_BYTES) {
                    throw new ZipException("Control ZIP expanded size is too large");
                }
                layoutContent = new String(jsonBytes, StandardCharsets.UTF_8);
                zipIn.closeEntry();
                continue;
            }

            bitmapCount++;
            if (bitmapCount > MAX_BITMAPS) throw new ZipException("Too many control bitmaps");

            byte[] imageBytes = readEntryLimited(zipIn, MAX_BITMAP_FILE_BYTES);
            expandedBytes += imageBytes.length;
            if (expandedBytes > MAX_TOTAL_EXPANDED_BYTES) {
                throw new ZipException("Control ZIP expanded size is too large");
            }
            layoutBitmaps.mBitmaps.put(entryName, decodeBitmapSafely(imageBytes, totalPixels));
            zipIn.closeEntry();
        }
        if(layoutContent == null) throw new ZipException("Incorrect ZIP file structure");
        return new ControlsContainer(layoutContent, layoutBitmaps);
    }

    private static boolean hasZipSignature(BufferedInputStream input) throws IOException {
        input.mark(8);
        int b0 = input.read();
        int b1 = input.read();
        int b2 = input.read();
        int b3 = input.read();
        input.reset();

        return b0 == 'P' && b1 == 'K'
                && ((b2 == 3 && b3 == 4)
                || (b2 == 5 && b3 == 6)
                || (b2 == 7 && b3 == 8));
    }

    private static String readRawJson(BufferedInputStream input) throws IOException {
        byte[] data = readEntryLimited(input, MAX_RAW_JSON_BYTES);
        return new String(data, StandardCharsets.UTF_8);
    }

    private static ControlsContainer load(FileInputStream fileInputStream, long fileSize) throws IOException{
        if (fileSize <= 0L) throw new EOFException("Empty control layout");
        if (fileSize > MAX_RAW_JSON_BYTES && fileSize > (8L * 1024L * 1024L)) {
            throw new IOException("Control layout file size too large");
        }

        try(BufferedInputStream bufferedIn = new BufferedInputStream(fileInputStream)) {
            if(hasZipSignature(bufferedIn)) {
                try(ZipInputStream zipIn = new ZipInputStream(bufferedIn)) {
                    return loadFromZip(zipIn);
                }
            }

            if(fileSize > MAX_RAW_JSON_BYTES) throw new IOException("Raw JSON control data size too large");
            return createEmpty(readRawJson(bufferedIn));
        }
    }

    private static void storeZip(FileOutputStream fileOutputStream, ControlsContainer controlsContainer) throws IOException {
        LayoutBitmaps bitmaps = controlsContainer.mLayoutZip;
        String controlsContent = controlsContainer.mControlsJson;
        try(ZipOutputStream zipOutputStream = new ZipOutputStream(fileOutputStream)) {
            zipOutputStream.putNextEntry(new ZipEntry("layout.json"));
            IOUtils.write(controlsContent, zipOutputStream, StandardCharsets.UTF_8);
            zipOutputStream.closeEntry();
            for(Map.Entry<String, Bitmap> bitmapEntry : bitmaps.mBitmaps.entrySet()) {
                Bitmap outBitmap = bitmapEntry.getValue();
                if(outBitmap == null) continue;
                zipOutputStream.putNextEntry(new ZipEntry(bitmapEntry.getKey()));
                outBitmap.compress(Bitmap.CompressFormat.WEBP, 100, zipOutputStream);
                zipOutputStream.closeEntry();
            }
        }
    }

    public static void store(FileOutputStream fileOutputStream, ControlsContainer controlsContainer) throws IOException {
        LayoutBitmaps bitmaps = controlsContainer.mLayoutZip;
        String controlsContent = controlsContainer.mControlsJson;
        if(bitmaps.mBitmaps.isEmpty()) {
            IOUtils.write(controlsContent, fileOutputStream, StandardCharsets.UTF_8);
            return;
        }
        storeZip(fileOutputStream, controlsContainer);
    }

    public static @NonNull ControlsContainer load(File jsonLocation) throws IOException {
        try (FileInputStream fileInputStream = new FileInputStream(jsonLocation)) {
            return load(fileInputStream, jsonLocation.length());
        }
    }

    public static final class ControlsContainer {
        public final String mControlsJson;
        public final LayoutBitmaps mLayoutZip;

        public ControlsContainer(String mControlsJson, LayoutBitmaps mLayoutZip) {
            this.mControlsJson = mControlsJson;
            this.mLayoutZip = mLayoutZip;
        }
    }
}
