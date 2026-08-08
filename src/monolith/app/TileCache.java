package monolith.app;

import monolith.core.ChunkGenerator;
import monolith.core.Coord;
import monolith.render.TileRenderer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 瓦片缓存 + 异步加载（对应网页的 Web Worker 池）。
 *
 * 后台线程负责 CPU 密集的瓦片栅格化（ChunkGenerator 不可变、线程安全），
 * 主线程只做 GL 纹理上传与绘制，因此缩放/平移时界面不会卡顿，瓦片渐进出现。
 *
 * 职责划分：
 *  - request(key)：请求后台生成某瓦片（去重，避免重复入队）。
 *  - uploadIfReady(key)：已就绪则上传为 GL 纹理并返回 id；否则返回 -1。
 *  - onZoomChanged(stride)：缩放级别变化时丢弃旧 stride 的排队/就绪/纹理。
 */
final class TileCache {
    private static final int TILE_SIZE = 256;
    private static final int NUM_WORKERS = 4;

    /** 瓦片键：由缩放级别 stride 与瓦片索引唯一确定。 */
    static final class Key {
        final int stride, tx, tz;
        Key(int stride, int tx, int tz) {
            this.stride = stride;
            this.tx = tx;
            this.tz = tz;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return stride == k.stride && tx == k.tx && tz == k.tz;
        }
        @Override public int hashCode() {
            return stride * 73856093 ^ tx * 19349663 ^ tz * 83492791;
        }
    }

    private final ChunkGenerator generator;
    private final int maxTiles;
    private final AtomicInteger currentStride;

    // 主线程访问：已上传的 GL 纹理（LRU，超限淘汰）
    private final Map<Key, Integer> textures;
    // 后台填充 / 主线程读取：刚栅格化、尚未上传的瓦片
    private final Map<Key, BufferedImage> readyImages;
    // 主线程去重：已排队 / 生成中 / 待上传的瓦片
    private final Map<Key, Boolean> requested;
    private final LinkedBlockingQueue<Key> queue;

    private final Thread[] workers;

    TileCache(ChunkGenerator generator, int maxTiles, int initialStride) {
        this.generator = generator;
        this.maxTiles = maxTiles;
        this.currentStride = new AtomicInteger(initialStride);
        this.textures = new LinkedHashMap<Key, Integer>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Key, Integer> eldest) {
                if (size() > maxTiles) {
                    GL11.glDeleteTextures(eldest.getValue());
                    requested.remove(eldest.getKey()); // 允许将来重新生成
                    return true;
                }
                return false;
            }
        };
        this.readyImages = new ConcurrentHashMap<>();
        this.requested = new ConcurrentHashMap<>();
        this.queue = new LinkedBlockingQueue<>();

        this.workers = new Thread[NUM_WORKERS];
        for (int i = 0; i < NUM_WORKERS; i++) {
            workers[i] = new Thread(this::workerLoop, "tile-loader-" + i);
            workers[i].setDaemon(true);
            workers[i].start();
        }
    }

    /** 后台线程主体：从队列取任务并栅格化瓦片。 */
    private void workerLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            Key key;
            try {
                key = queue.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (key == null) {
                continue;
            }
            if (key.stride != currentStride.get()) {
                continue; // 已被更新的缩放级别取代，丢弃
            }
            BufferedImage img = generate(key);
            if (key.stride != currentStride.get()) {
                continue; // 生成期间缩放级别又变了，丢弃结果
            }
            readyImages.put(key, img);
        }
    }

    /** 栅格化一个瓦片（CPU 密集，在后台线程执行）。 */
    private BufferedImage generate(Key key) {
        int tileWorld = TILE_SIZE * key.stride * 4;
        Coord.BlockPos2D start = new Coord.BlockPos2D(key.tx * tileWorld, key.tz * tileWorld);
        return TileRenderer.renderTile(generator, start, key.stride);
    }

    /** 主线程调用：请求后台生成某瓦片（若尚未生成/上传）。 */
    void request(Key key) {
        if (textures.containsKey(key) || requested.containsKey(key)) {
            return;
        }
        requested.put(key, Boolean.TRUE);
        queue.offer(key);
    }

    /** 主线程调用：判断瓦片是否已上传或已栅格化就绪（无 GL 副作用，可在 glBegin 前调用）。 */
    boolean isReady(Key key) {
        return textures.containsKey(key) || readyImages.containsKey(key);
    }

    /**
     * 主线程调用：若瓦片纹理已就绪则返回 GL 纹理 id；
     * 若栅格化完成但尚未上传，则上传并返回 id；否则返回 -1（未就绪，本帧跳过）。
     */
    int uploadIfReady(Key key) {
        Integer tex = textures.get(key);
        if (tex != null) {
            return tex;
        }
        BufferedImage img = readyImages.get(key);
        if (img == null) {
            return -1;
        }
        int id = upload(img);
        textures.put(key, id);
        readyImages.remove(key);
        requested.remove(key);
        return id;
    }

    /** 缩放级别变化时调用：抛弃旧 stride 的排队/就绪/纹理。 */
    void onZoomChanged(int stride) {
        currentStride.set(stride);
        queue.clear();
        readyImages.clear();
        requested.clear();
        clear();
    }

    /** 释放所有 GL 纹理并清空状态。 */
    void clear() {
        for (Integer id : textures.values()) {
            GL11.glDeleteTextures(id);
        }
        textures.clear();
        readyImages.clear();
        requested.clear();
        queue.clear();
    }

    /** 停止后台线程。 */
    void shutdown() {
        for (Thread w : workers) {
            w.interrupt();
        }
    }

    /** 把 BufferedImage 上传为 GL_RGBA 纹理。 */
    private static int upload(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                buf.put((byte) ((argb >> 16) & 0xFF)); // R
                buf.put((byte) ((argb >> 8) & 0xFF));  // G
                buf.put((byte) (argb & 0xFF));         // B
                buf.put((byte) ((argb >> 24) & 0xFF)); // A
            }
        }
        buf.flip();

        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        // 放大过滤用 LINEAR：mag>1 额外拉近时平滑模糊（接近网页效果），而非马赛克
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        // 关键：CLAMP_TO_EDGE (0x812F) 避免瓦片边界因 GL_REPEAT 把 v=1.0 回绕到 texel 0 而产生接缝线
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, 0x812F);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, 0x812F);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        return id;
    }
}