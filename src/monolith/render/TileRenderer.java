package monolith.render;

import monolith.core.ChunkGenerator;
import monolith.core.Coord;
import monolith.core.Finder;

import java.awt.image.BufferedImage;

/**
 * 瓦片渲染器：把一块 256x256 采样区域渲染成 BufferedImage。
 * 对应 monolith-renderer/src/lib.rs 的 render_section_to_buf_blip。
 */
public final class TileRenderer {
    public static final int TILE_SIZE = 256;

    private TileRenderer() {}

    /** 颜色对应 lib.rs 中的 COLOR_*（ARGB）。 */
    public static int colorFor(Finder.PointResult r) {
        switch (r) {
            case MONOLITH: return 0xFFFF0000;           // 红 (255,0,0)
            case LAND:     return 0xFF8DB360;           // 绿 (141,179,96)
            case WATER:    return 0xFF000056;           // 蓝 (0,0,86)
            case FAR_LANDS_CORNER: return 0xFFFF6A00;   // 亮橙 (255,106,0)
            case FAR_LANDS:        return 0xFF7F3300;   // 深橙 (127,51,0)
            case OOB:      return 0x00000000;           // 透明
            default:       return 0x00000000;
        }
    }

    /**
     * 渲染一块瓦片。
     *
     * @param gen       区块生成器
     * @param startBlock 瓦片起始方块坐标（左上角）
     * @param stride     采样步长（每个采样点间隔 stride 方块，即 web 的 scale_factor）
     * @return 256x256 的 ARGB 图像
     */
    public static BufferedImage renderTile(ChunkGenerator gen, Coord.BlockPos2D startBlock, int stride) {
        BufferedImage img = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Coord.SamplePos2D startSample = startBlock.toSample();
        for (int px = 0; px < TILE_SIZE; px++) {
            for (int pz = 0; pz < TILE_SIZE; pz++) {
                Coord.SamplePos2D pos = new Coord.SamplePos2D(
                        startSample.x + stride * px,
                        startSample.z + stride * pz);
                Finder.PointResult r = Finder.inspectPoint(gen, pos);
                img.setRGB(px, pz, colorFor(r));
            }
        }
        return img;
    }
}