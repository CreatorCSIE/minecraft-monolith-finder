package monolith.core;

/** 描述要采样的 3D 噪声区域，对应 Rust 的 cuboid.rs。 */
public final class SamplingCuboid {
    public final Coord.SamplePos3D startPos;
    public final int xExtent;
    public final int yExtent;
    public final int zExtent;
    public final double xScale;
    public final double yScale;
    public final double zScale;

    public SamplingCuboid(Coord.SamplePos3D startPos, int xExtent, int yExtent, int zExtent,
                          double xScale, double yScale, double zScale) {
        this.startPos = startPos;
        this.xExtent = xExtent;
        this.yExtent = yExtent;
        this.zExtent = zExtent;
        this.xScale = xScale;
        this.yScale = yScale;
        this.zScale = zScale;
    }

    /** 仅缩放 *_scale 字段（与 Rust scale_all 一致）。 */
    public SamplingCuboid scaleAll(double factor) {
        return new SamplingCuboid(startPos, xExtent, yExtent, zExtent,
                xScale * factor, yScale * factor, zScale * factor);
    }

    public double scaledX(int index) {
        return (double) (startPos.x + index) * xScale;
    }

    public double scaledY(int index) {
        return (double) (startPos.y + index) * yScale;
    }

    public double scaledZ(int index) {
        return (double) (startPos.z + index) * zScale;
    }

    public int len() {
        return xExtent * yExtent * zExtent;
    }
}