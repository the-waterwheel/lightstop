package android.media;

import android.graphics.ImageFormat;
import java.nio.ByteBuffer;
import java.util.Random;

/** Synthetic transport only: every 2x2 Bayer cell has an independent neutral signal. */
public final class ResearchRawImage extends Image {
    private final ByteBuffer bytes = ByteBuffer.allocate(512 * 512 * 2);
    private final Plane[] planes;
    public ResearchRawImage(long seed) {
        Random random = new Random(seed);
        for (int y = 0; y < 512; y += 2) for (int x = 0; x < 512; x += 2) {
            int value = 256 + random.nextInt(3500);
            for (int dy = 0; dy < 2; ++dy) for (int dx = 0; dx < 2; ++dx) {
                int offset = ((y + dy) * 512 + x + dx) * 2;
                bytes.put(offset, (byte) value);
                bytes.put(offset + 1, (byte) (value >> 8));
            }
        }
        planes = new Plane[] { new Plane() {
            @Override public int getRowStride() { return 512 * 2; }
            @Override public int getPixelStride() { return 2; }
            @Override public ByteBuffer getBuffer() { return bytes; }
        } };
    }
    @Override public int getFormat() { return ImageFormat.RAW_SENSOR; }
    @Override public int getWidth() { return 512; }
    @Override public int getHeight() { return 512; }
    @Override public long getTimestamp() { return 1; }
    @Override public void setTimestamp(long timestamp) { }
    @Override public Plane[] getPlanes() { return planes; }
    @Override public void close() { }
}
