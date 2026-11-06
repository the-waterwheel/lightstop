package android.media;

import android.graphics.ImageFormat;
import java.nio.ByteBuffer;

/** Test-only package peer: SDK Image/Plane constructors have package visibility. No HAL buffers. */
public final class EvaluativeTestImage extends Image {
    private boolean closed;
    private final Plane[] planes;
    public EvaluativeTestImage(boolean truncated) {
        planes = new Plane[] {new TestPlane(8,8,10,1,3,truncated),
                new TestPlane(4,4,9,2,2,false), new TestPlane(4,4,9,2,2,false)};
    }
    public EvaluativeTestImage(int luma) {
        this(false);
        ByteBuffer yPlane = planes[0].getBuffer();
        for (int y = 0; y < 8; ++y) for (int x = 0; x < 8; ++x)
            yPlane.put(yPlane.position() + y * planes[0].getRowStride() + x, (byte) luma);
    }
    public boolean isClosed() { return closed; }
    @Override public int getFormat() { return ImageFormat.YUV_420_888; }
    @Override public int getWidth() { return 8; }
    @Override public int getHeight() { return 8; }
    @Override public long getTimestamp() { return 1; }
    @Override public void setTimestamp(long timestamp) { }
    @Override public Plane[] getPlanes() { return planes; }
    @Override public void close() { closed = true; }

    private static final class TestPlane extends Image.Plane {
        private final int stride, pixel;
        private final ByteBuffer buffer;
        TestPlane(int width, int height, int stride, int pixel, int offset, boolean truncated) {
            this.stride = stride;
            this.pixel = pixel;
            buffer = ByteBuffer.allocate(offset + stride * height);
            for (int y = 0; y < height; ++y) for (int x = 0; x < width; ++x)
                buffer.put(offset + y * stride + x * pixel, (byte) 128);
            buffer.position(offset);
            if (truncated) buffer.limit(offset + stride);
        }
        @Override public int getRowStride() { return stride; }
        @Override public int getPixelStride() { return pixel; }
        @Override public ByteBuffer getBuffer() { return buffer; }
    }
}
