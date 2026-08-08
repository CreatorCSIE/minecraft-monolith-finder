package monolith.core;

/** 区块生成器，对应 worldgen/chunk_gen.rs。 */
public final class ChunkGenerator {
    private final ScaledFractalNoise hillNoise;
    private final ScaledFractalNoise depthNoise;

    public ChunkGenerator(long seed) {
        java.util.Random random = new java.util.Random(seed);
        // 与原版完全一致的丢弃顺序
        ScaledFractalNoise.discardNoise(random, 16);
        ScaledFractalNoise.discardNoise(random, 16);
        ScaledFractalNoise.discardNoise(random, 8);
        ScaledFractalNoise.discardNoise(random, 4);
        ScaledFractalNoise.discardNoise(random, 4);
        hillNoise = new ScaledFractalNoise(random, 1.0, 0.0, 10);
        depthNoise = new ScaledFractalNoise(random, 100.0, 0.0, 16);
    }

    public ScaledFractalNoise hillNoise() {
        return hillNoise;
    }

    public ScaledFractalNoise depthNoise() {
        return depthNoise;
    }
}