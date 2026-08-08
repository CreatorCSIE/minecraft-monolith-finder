package monolith;

import monolith.core.ChunkGenerator;
import monolith.core.Coord;
import monolith.core.Finder;
import monolith.core.FractalNoise;
import monolith.core.SampleJobImpl;
import monolith.core.SamplingCuboid;
import monolith.render.TileRenderer;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.Random;

/**
 * 验证核心移植是否与 Rust 测试向量一致。
 *
 * 编译运行：
 *   javac -encoding UTF-8 -d out src\monolith\core\*.java src\monolith\render\TileRenderer.java src\monolith\Verify.java
 *   java -cp out monolith.Verify
 */
public final class Verify {
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        // 1) fractal 测试向量（Rust fractal.rs 的 basic_data_matches）
        Random random = new Random(15);
        FractalNoise noise = new FractalNoise(random, 16);
        double[] results = new double[16 * 4 * 29];
        SamplingCuboid cuboid = new SamplingCuboid(
                new Coord.SamplePos3D(15, 52, 6), 16, 4, 29,
                0.512386, 198.1293, 9999.1283);
        SampleJobImpl job = noise.beginSamplingInto(cuboid, results);
        job.sampleAll();
        check("fractal[592] == 10828.95355391629", results[592], 10828.95355391629, 1e-8);

        // 2) hill 噪声测试向量（Rust chunk_gen.rs 的 hill_noise_is_correct）
        ChunkGenerator gen = new ChunkGenerator(8676641231682978167L);
        double[] hillBuf = new double[]{0.0};
        SampleJobImpl hillJob = gen.hillNoise().sample2d(new Coord.SamplePos2D(-656, 1084), 1, 1, hillBuf);
        hillJob.sampleAll();
        check("hill_noise(-656,1084) == -523.681051", hillBuf[0], -523.681051, 1e-4);

        // 2.5) 快速路径 vs 原始路径：在大范围采样点上逐一比对
        int mismatches = 0;
        int compared = 0;
        for (int x = -4000; x <= 4000; x += 13) {
            for (int z = -4000; z <= 4000; z += 17) {
                Coord.SamplePos2D p = new Coord.SamplePos2D(x, z);
                Finder.PointResult fast = Finder.inspectPoint(gen, p);
                Finder.PointResult slow = Finder.inspectPointSlow(gen, p);
                compared++;
                if (fast != slow) {
                    if (mismatches < 10) {
                        System.out.printf("  不一致: pos=(%d,%d) fast=%s slow=%s%n", x, z, fast, slow);
                    }
                    mismatches++;
                }
            }
        }
        System.out.printf("快速/原始路径比对: %d 个点, %d 个不一致%n", compared, mismatches);
        if (mismatches > 0) {
            failures++;
        }

        // 3) 渲染一张瓦片并输出 PNG（无 LWJGL2 依赖，便于先看效果）
        File dir = new File("out");
        if (!dir.exists()) {
            dir = new File(".");
        }
        int stride = 1;
        java.awt.image.BufferedImage img =
                TileRenderer.renderTile(gen, new Coord.BlockPos2D(-2624, 4343), stride);
        File png = new File(dir, "tile_preview.png");
        ImageIO.write(img, "png", png);
        System.out.println("已输出瓦片预览: " + png.getAbsolutePath());

        if (failures == 0) {
            System.out.println("全部测试通过 ✓");
        } else {
            System.out.println("有 " + failures + " 项失败 ✗");
            System.exit(1);
        }
    }

    private static void check(String name, double actual, double expected, double eps) {
        double diff = Math.abs(actual - expected);
        boolean ok = diff <= eps;
        System.out.printf("%-40s actual=%.12f expected=%.12f -> %s%n",
                name, actual, expected, ok ? "OK" : "FAIL");
        if (!ok) {
            failures++;
        }
    }
}