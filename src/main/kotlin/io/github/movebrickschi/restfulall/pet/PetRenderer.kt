package io.github.movebrickschi.restfulall.pet

import java.awt.Graphics2D
import java.awt.RenderingHints

/**
 * 宠物情绪状态。
 *
 * 每项绑定一张 PNG sheet（位于 `resources/pet/sprites/<spriteName>.png`）和动画元数据：
 * - [frameCount] 帧数（用于循环播放）
 * - [fps]        逻辑播放速度（实际推进由 [PetStatusBarWidget] 的 timer 与 frameDurationMs 控制）
 * - [loops]      是否循环播放；false 表示一次性动作（idle 变体）播完后会自动回到 IDLE
 */
enum class PetMood(
    val spriteName: String,
    val frameCount: Int,
    val fps: Int,
    val loops: Boolean = true,
) {
    IDLE(spriteName = "idle", frameCount = 8, fps = 6),
    HAPPY(spriteName = "run", frameCount = 8, fps = 12),
    TIRED(spriteName = "idle", frameCount = 8, fps = 3),
    CLICK(spriteName = "jump", frameCount = 3, fps = 12, loops = false),
    LICK(spriteName = "attack", frameCount = 8, fps = 8, loops = false),
    STRETCH(spriteName = "walk", frameCount = 12, fps = 6, loops = false),
}

/**
 * 把指定 mood 的当前帧绘制到目标画布。
 *
 * 用 NEAREST_NEIGHBOR 缩放避免像素艺术被模糊化。
 * 完整保留精灵的透明背景，绘制位置位于 (0,0) 起点的 [width] x [height] 区域内。
 */
object PetRenderer {

    fun paint(g2: Graphics2D, mood: PetMood, width: Int, height: Int, frameIdx: Int) {
        val sprite = SpriteSheet.frame(mood, frameIdx)
        g2.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
        )
        g2.setRenderingHint(
            RenderingHints.KEY_ANTIALIASING,
            RenderingHints.VALUE_ANTIALIAS_OFF,
        )
        g2.drawImage(sprite, 0, 0, width, height, null)
    }
}
