package monolith.core;

/**
 * 采样任务实现，对应 Rust 的 SampleJobImpl。
 * 每次 sampleOnce 应用一层 perlin（intensity = 2^(remaining-1)），
 * 并对 cuboid 缩放 1/intensity。
 */
public final class SampleJobImpl implements SamplingJob {
    private final PerlinNoise[] noise;
    private int appliedNoises = 0;
    private final double[] results;
    private final SamplingCuboid cuboid;

    SampleJobImpl(PerlinNoise[] noise, double[] results, SamplingCuboid cuboid) {
        if (results.length != cuboid.len()) {
            throw new IllegalArgumentException("results 长度必须与 cuboid 维度匹配");
        }
        this.noise = noise;
        this.results = results;
        this.cuboid = cuboid;
    }

    public void sampleOnce() {
        if (appliedNoises < noise.length) {
            int remaining = noise.length - appliedNoises;
            double intensity = Math.pow(2.0, remaining - 1);
            noise[appliedNoises].sampleCuboid(results, cuboid.scaleAll(1.0 / intensity), intensity);
            appliedNoises++;
        }
    }

    public double[] results() {
        return results;
    }

    public int remainingSteps() {
        return noise.length - appliedNoises;
    }

    public double remainingVariation() {
        return (Math.pow(2.0, remainingSteps()) - 1.0) * PerlinNoise.RESULT_RANGE;
    }

    public boolean isDone() {
        return appliedNoises == noise.length;
    }

    public SamplingStatus status() {
        if (appliedNoises == 0) {
            return SamplingStatus.NOT_STARTED;
        }
        if (appliedNoises == noise.length) {
            return SamplingStatus.DONE;
        }
        return SamplingStatus.STARTED;
    }

    /** 采样直到完成，返回结果数组。 */
    public double[] sampleAll() {
        while (!isDone()) {
            sampleOnce();
        }
        return results;
    }
}