package monolith.core;

/**
 * 多 octave 噪声，合并指数衰减强度的若干层 perlin，对应 fractal.rs。
 * 构造时按顺序生成 octaves，再反转存储（与原版一致，影响随机流消费顺序）。
 */
public final class FractalNoise {
    private final PerlinNoise[] octaves;

    public FractalNoise(java.util.Random random, int octaveCount) {
        PerlinNoise[] tmp = new PerlinNoise[octaveCount];
        for (int i = 0; i < octaveCount; i++) {
            tmp[i] = new PerlinNoise(random);
        }
        this.octaves = new PerlinNoise[octaveCount];
        for (int i = 0; i < octaveCount; i++) {
            this.octaves[i] = tmp[octaveCount - 1 - i];
        }
    }

    PerlinNoise[] octaves() {
        return octaves;
    }

    public SampleJobImpl beginSamplingInto(SamplingCuboid cuboid, double[] results) {
        return new SampleJobImpl(octaves, results, cuboid);
    }
}