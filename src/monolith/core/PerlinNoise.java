package monolith.core;

/**
 * Minecraft 旧版的单层 3D Perlin 噪声，复刻 perlin.rs。
 * 注意：Minecraft 相关版本使用了"错误"的 perlin 算法，本实现保留该行为
 * （仅在 cubeY 变化时重算 lerp0-3，忽略 yPos 依赖，与原版一致）。
 */
public final class PerlinNoise {
    public static final double RESULT_RANGE = 1.0;

    private final int[] permutations = new int[512];
    private final double xOffset;
    private final double yOffset;
    private final double zOffset;

    public PerlinNoise(java.util.Random random) {
        xOffset = random.nextDouble() * 256.0;
        yOffset = random.nextDouble() * 256.0;
        zOffset = random.nextDouble() * 256.0;
        for (int i = 0; i < 256; i++) {
            permutations[i] = i;
        }
        for (int i = 0; i < 256; i++) {
            int n = random.nextInt(256 - i) + i;
            int tmp = permutations[i];
            permutations[i] = permutations[n];
            permutations[n] = tmp;
        }
        for (int i = 256; i < 512; i++) {
            permutations[i] = permutations[i - 256];
        }
    }

    double xOffset() { return xOffset; }
    double yOffset() { return yOffset; }
    double zOffset() { return zOffset; }

    private static double fade(double x) {
        return x * x * x * (x * (x * 6.0 - 15.0) + 10.0);
    }

    private static double lerp(double sel, double low, double high) {
        return low + sel * (high - low);
    }

    private static double grad(int hash, double x, double y, double z) {
        hash &= 0xF;
        double u = hash < 8 ? x : y;
        double v = hash < 4 ? y : (hash == 12 || hash == 14 ? x : z);
        return ((hash & 0x1) == 0 ? u : -u) + ((hash & 0x2) == 0 ? v : -v);
    }

    /** 采样一个 cuboid 的噪声。结果乘以 intensity 累加到 arr。
     * 点 (x,y,z) 存储在 arr[y + resY*(z + resZ*x)]。
     */
    public void sampleCuboid(double[] arr, SamplingCuboid cuboid, double intensity) {
        int outputIdx = 0;
        double lerp0 = 0.0, lerp1 = 0.0, lerp2 = 0.0, lerp3 = 0.0;
        int lastCubeY = -1;
        for (int xIdx = 0; xIdx < cuboid.xExtent; xIdx++) {
            for (int zIdx = 0; zIdx < cuboid.zExtent; zIdx++) {
                for (int yIdx = 0; yIdx < cuboid.yExtent; yIdx++) {
                    double xPosBase = cuboid.scaledX(xIdx) + xOffset;
                    double yPosBase = cuboid.scaledY(yIdx) + yOffset;
                    double zPosBase = cuboid.scaledZ(zIdx) + zOffset;
                    int cubeX = (int) Math.floor(xPosBase) & 0xFF;
                    int cubeY = (int) Math.floor(yPosBase) & 0xFF;
                    int cubeZ = (int) Math.floor(zPosBase) & 0xFF;
                    double xPos = xPosBase - Math.floor(xPosBase);
                    double yPos = yPosBase - Math.floor(yPosBase);
                    double zPos = zPosBase - Math.floor(zPosBase);
                    double u = fade(xPos);
                    double v = fade(yPos);
                    double w = fade(zPos);

                    // 与原版 identical 的"错误"缓存：仅当 cubeY 变化时才重算
                    if (yIdx == 0 || cubeY != lastCubeY) {
                        lastCubeY = cubeY;
                        int bigA = permutations[cubeX] + cubeY;
                        int bigAA = permutations[bigA] + cubeZ;
                        int bigAB = permutations[bigA + 1] + cubeZ;
                        int bigB = permutations[cubeX + 1] + cubeY;
                        int bigBA = permutations[bigB] + cubeZ;
                        int bigBB = permutations[bigB + 1] + cubeZ;

                        lerp0 = lerp(u,
                                grad(permutations[bigAA], xPos, yPos, zPos),
                                grad(permutations[bigBA], xPos - 1.0, yPos, zPos));
                        lerp1 = lerp(u,
                                grad(permutations[bigAB], xPos, yPos - 1.0, zPos),
                                grad(permutations[bigBB], xPos - 1.0, yPos - 1.0, zPos));
                        lerp2 = lerp(u,
                                grad(permutations[bigAA + 1], xPos, yPos, zPos - 1.0),
                                grad(permutations[bigBA + 1], xPos - 1.0, yPos, zPos - 1.0));
                        lerp3 = lerp(u,
                                grad(permutations[bigAB + 1], xPos, yPos - 1.0, zPos - 1.0),
                                grad(permutations[bigBB + 1], xPos - 1.0, yPos - 1.0, zPos - 1.0));
                    }
                    arr[outputIdx] += lerp(w, lerp(v, lerp0, lerp1), lerp(v, lerp2, lerp3)) * intensity;
                    outputIdx++;
                }
            }
        }
    }

    /**
     * 采样单个点（1x1x1），用于快速路径。逻辑与 sampleCuboid 的 yIdx=0 完全一致
     * （yIdx==0 时总是重算 lerp0-3，因此无需缓存分支）。
     */
    double sampleSingle(double xPosBase, double yPosBase, double zPosBase) {
        int cubeX = (int) Math.floor(xPosBase) & 0xFF;
        int cubeY = (int) Math.floor(yPosBase) & 0xFF;
        int cubeZ = (int) Math.floor(zPosBase) & 0xFF;
        double xPos = xPosBase - Math.floor(xPosBase);
        double yPos = yPosBase - Math.floor(yPosBase);
        double zPos = zPosBase - Math.floor(zPosBase);
        double u = fade(xPos);
        double v = fade(yPos);
        double w = fade(zPos);
        int bigA = permutations[cubeX] + cubeY;
        int bigAA = permutations[bigA] + cubeZ;
        int bigAB = permutations[bigA + 1] + cubeZ;
        int bigB = permutations[cubeX + 1] + cubeY;
        int bigBA = permutations[bigB] + cubeZ;
        int bigBB = permutations[bigB + 1] + cubeZ;
        double lerp0 = lerp(u, grad(permutations[bigAA], xPos, yPos, zPos),
                grad(permutations[bigBA], xPos - 1.0, yPos, zPos));
        double lerp1 = lerp(u, grad(permutations[bigAB], xPos, yPos - 1.0, zPos),
                grad(permutations[bigBB], xPos - 1.0, yPos - 1.0, zPos));
        double lerp2 = lerp(u, grad(permutations[bigAA + 1], xPos, yPos, zPos - 1.0),
                grad(permutations[bigBA + 1], xPos - 1.0, yPos, zPos - 1.0));
        double lerp3 = lerp(u, grad(permutations[bigAB + 1], xPos, yPos - 1.0, zPos - 1.0),
                grad(permutations[bigBB + 1], xPos - 1.0, yPos - 1.0, zPos - 1.0));
        return lerp(w, lerp(v, lerp0, lerp1), lerp(v, lerp2, lerp3));
    }
}