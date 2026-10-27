package android.media;

import android.graphics.ImageFormat;
import java.nio.ByteBuffer;

/** Synthetic Bayer image: neutral halves differ by precisely one stop. */
public final class RecordedRawTestImage extends Image {
    private final ByteBuffer bytes = ByteBuffer.allocate(512 * 384 * 2);
    public RecordedRawTestImage() {
        for (int y = 0; y < 384; y++) for (int x = 0; x < 512; x++) {
            int value = x < 256 ? 1024 : 2048;
            int offset = (y * 512 + x) * 2;
            bytes.put(offset, (byte) value);
            bytes.put(offset + 1, (byte) (value >> 8));
        }
    }
    public RecordedRawTestImage(int red, int green, int blue) {
        for (int y = 0; y < 384; y++) for (int x = 0; x < 512; x++) {
            int value = (y % 2 == 0 && x % 2 == 0) ? red : (y % 2 == 1 && x % 2 == 1) ? blue : green;
            int offset = (y * 512 + x) * 2;
            bytes.put(offset, (byte) value);
            bytes.put(offset + 1, (byte) (value >> 8));
        }
    }
    @Override public int getFormat() { return ImageFormat.RAW_SENSOR; }
    @Override public int getWidth() { return 512; }
    @Override public int getHeight() { return 384; }
    @Override public long getTimestamp() { return 1; }
    @Override public void setTimestamp(long timestamp) { }
    @Override public Plane[] getPlanes() { return new Plane[] { new Plane() {
        @Override public int getRowStride() { return 1024; }
        @Override public int getPixelStride() { return 2; }
        @Override public ByteBuffer getBuffer() { return bytes; }
    } }; }
    @Override public void close() { }
}
