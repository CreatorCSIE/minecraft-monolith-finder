package monolith.core;

/**
 * Monolith 查找器，对应 finder/mod.rs。
 * 判定给定采样点的地形类型。
 *
 * inspectPoint 使用无分配的单点快速路径（searchFast + PerlinNoise.sampleSingle），
 * 复刻了 search/remaining_variation 的提前终止逻辑；inspectPointSlow 保留原始的
 * 分形任务路径，用于逐一比对、验证快速路径 bit-exact。
 */
public final class Finder {
    private Finder() {}

    public static final int WORLD_BORDER = 32_000_000;
    public static final int FAR_LANDS = 12_550_824;

    /** POW2[i] = 2^i（整数次幂，双精度精确）。 */
    private static final double[] POW2 = new double[17];
    static {
        for (int i = 0; i < POW2.length; i++) {
            POW2[i] = Double.longBitsToDouble(((long) (1023 + i)) << 52);
        }
    }

    // 复用约束对象，避免每个像素分配
    private static final Search.Constraint ABS_GEQ_8000 = Search.absoluteGreaterEquals(8000.0);
    private static final Search.Constraint LESS_NEG512 = Search.less(-512.0);

    /** 地形类型。 */
    public enum PointResult {
        LAND,
        WATER,
        MONOLITH,
        FAR_LANDS_CORNER, // 对应 FarLandsKind::Corner
        FAR_LANDS,        // 对应 FarLandsKind::X 或 ::Z
        OOB
    }

    /** 判定给定采样点的地形（快速路径，无分配）。对应 inspect_point。 */
    public static PointResult inspectPoint(ChunkGenerator gen, Coord.SamplePos2D pos) {
        Coord.BlockPos2D block = pos.toBlock();
        int ax = Math.abs(block.x);
        int az = Math.abs(block.z);
        if (ax >= WORLD_BORDER || az >= WORLD_BORDER) {
            return PointResult.OOB;
        }
        if (ax >= FAR_LANDS && az >= FAR_LANDS) {
            return PointResult.FAR_LANDS_CORNER;
        }
        if (ax >= FAR_LANDS || az >= FAR_LANDS) {
            return PointResult.FAR_LANDS;
        }

        boolean isLand = searchFast(gen.depthNoise(), pos, ABS_GEQ_8000);
        if (!isLand) {
            return PointResult.WATER;
        }
        boolean isCandidate = searchFast(gen.hillNoise(), pos, LESS_NEG512);
        return isCandidate ? PointResult.MONOLITH : PointResult.LAND;
    }

    /**
     * 单点约束搜索（无分配）。对应 search() + sample_once() 的逐 octave 提前终止。
     * 噪声基 cuboid：startPos=(pos.x, 0, pos.z)，extent=1x1x1，scale=(scaleXZ, 0, scaleXZ)。
     */
    private static boolean searchFast(ScaledFractalNoise sn, Coord.SamplePos2D pos, Search.Constraint c) {
        PerlinNoise[] octaves = sn.fractalNoise().octaves();
        double baseScale = sn.scaleXZ();
        int total = octaves.length;
        double value = 0.0;
        int applied = 0;
        while (true) {
            double remainingVariation = (POW2[total - applied] - 1.0) * PerlinNoise.RESULT_RANGE;
            Search.Result r = c.isFound(value, remainingVariation);
            if (r == Search.Result.FOUND) {
                return true;
            }
            if (r == Search.Result.NOT_FOUND) {
                return false;
            }
            int remaining = total - applied;
            double intensity = POW2[remaining - 1];
            double scaleFactor = 1.0 / intensity;
            PerlinNoise p = octaves[applied];
            double xPosBase = pos.x * (baseScale * scaleFactor) + p.xOffset();
            double zPosBase = pos.z * (baseScale * scaleFactor) + p.zOffset();
            double yPosBase = p.yOffset(); // startPos.y=0 且 yScale=0
            value += p.sampleSingle(xPosBase, yPosBase, zPosBase) * intensity;
            applied++;
        }
    }

    /** 原始路径（分形任务 + search），仅用于与快速路径比对。 */
    public static PointResult inspectPointSlow(ChunkGenerator chunkGen, Coord.SamplePos2D pos) {
        Coord.BlockPos2D block = pos.toBlock();
        int ax = Math.abs(block.x);
        int az = Math.abs(block.z);
        if (ax >= WORLD_BORDER || az >= WORLD_BORDER) {
            return PointResult.OOB;
        }
        if (ax >= FAR_LANDS && az >= FAR_LANDS) {
            return PointResult.FAR_LANDS_CORNER;
        }
        if (ax >= FAR_LANDS || az >= FAR_LANDS) {
            return PointResult.FAR_LANDS;
        }

        double[] depthBuf = new double[]{0.0};
        SamplingJob depthJob = chunkGen.depthNoise().sample2d(pos, 1, 1, depthBuf);
        boolean isLand = Search.search(depthJob, Search.absoluteGreaterEquals(8000.0));
        if (!isLand) {
            return PointResult.WATER;
        }

        double[] hillBuf = new double[]{0.0};
        SamplingJob hillJob = chunkGen.hillNoise().sample2d(pos, 1, 1, hillBuf);
        boolean isCandidate = Search.search(hillJob, Search.less(-512.0));
        if (!isCandidate) {
            return PointResult.LAND;
        }

        return PointResult.MONOLITH;
    }
}