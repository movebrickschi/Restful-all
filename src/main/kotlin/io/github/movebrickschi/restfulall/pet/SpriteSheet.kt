package io.github.movebrickschi.restfulall.pet

import com.intellij.openapi.diagnostic.Logger
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * 像素小猫精灵图加载器。
 *
 * 资源约定：每个动作一张水平排列的 PNG，位于 `resources/pet/sprites/<name>.png`，
 * 单帧固定 [CELL_W] x [CELL_H]。加载失败时回落到 [fallbackFrame]。
 */
object SpriteSheet {

    const val CELL_W = 80
    const val CELL_H = 64

    private const val BASE_PATH = "/pet/sprites/"
    private val log = Logger.getInstance(SpriteSheet::class.java)

    private val cache: MutableMap<String, Array<BufferedImage>> = HashMap()

    /**
     * 取指定 mood 的第 [index] 帧。index 会自动按帧数取模，越界安全。
     * 加载失败时返回程序内绘制的兜底图，保证调用方不需要判 null。
     */
    fun frame(mood: PetMood, index: Int): BufferedImage {
        val frames = framesFor(mood)
        if (frames.isEmpty()) return fallbackFrame
        return frames[((index % frames.size) + frames.size) % frames.size]
    }

    private fun framesFor(mood: PetMood): Array<BufferedImage> {
        val key = mood.spriteName
        cache[key]?.let { return it }
        val loaded = loadSheet(key, mood.frameCount)
        cache[key] = loaded
        return loaded
    }

    private fun loadSheet(name: String, expected: Int): Array<BufferedImage> {
        val resourcePath = "$BASE_PATH$name.png"
        val url = SpriteSheet::class.java.getResource(resourcePath)
        if (url == null) {
            log.warn("Pet sprite not found: $resourcePath")
            return emptyArray()
        }
        return try {
            val sheet = ImageIO.read(url) ?: return emptyArray()
            val cols = (sheet.width / CELL_W).coerceAtLeast(1)
            val frameCount = minOf(cols, expected)
            Array(frameCount) { i ->
                sheet.getSubimage(i * CELL_W, 0, CELL_W, CELL_H)
            }
        } catch (t: Throwable) {
            log.warn("Failed to load pet sprite $resourcePath", t)
            emptyArray()
        }
    }

    /** 资源全部缺失时使用的兜底帧：纯色圆点，足够提示"猫加载失败"。 */
    private val fallbackFrame: BufferedImage by lazy {
        val img = BufferedImage(CELL_W, CELL_H, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.color = Color(0xFF, 0x9F, 0x43)
            g.fillOval(CELL_W / 2 - 14, CELL_H / 2 - 14, 28, 28)
            g.color = Color.BLACK
            g.fillOval(CELL_W / 2 - 8, CELL_H / 2 - 4, 4, 4)
            g.fillOval(CELL_W / 2 + 4, CELL_H / 2 - 4, 4, 4)
        } finally {
            g.dispose()
        }
        img
    }
}
