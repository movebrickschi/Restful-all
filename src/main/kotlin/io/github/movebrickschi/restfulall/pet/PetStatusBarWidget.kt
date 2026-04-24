package io.github.movebrickschi.restfulall.pet

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.messages.MessageBusConnection
import io.github.movebrickschi.restfulall.MyMessageBundle
import io.github.movebrickschi.restfulall.humanize.FunMessageProvider
import io.github.movebrickschi.restfulall.service.PluginSettingsState
import io.github.movebrickschi.restfulall.stats.RouteNavigationListener
import io.github.movebrickschi.restfulall.stats.RouteStatsService
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.Timer

/**
 * 状态栏卡通像素小猫宠物（Pro）。
 *
 * 状态机：
 * - IDLE      : 默认呼吸 + 偶尔自动切到 LICK / STRETCH 变体
 * - HAPPY     : 跳转后 2.5s
 * - CLICK     : 点击后播放完 jump（一次性）
 * - TIRED     : 今日跳转 > 50
 * - LICK      : idle 期间 15% 概率出现，一次播放完回 IDLE
 * - STRETCH   : idle 期间 5% 概率出现，一次播放完回 IDLE
 *
 * 订阅 [RouteNavigationListener] 获取跳转事件，不直接依赖 UI 层。
 */
class PetStatusBarWidget(private val project: Project) : CustomStatusBarWidget {

    private val component: PetComponent = PetComponent(project)
    private var connection: MessageBusConnection? = null

    override fun ID(): String = WIDGET_ID
    override fun getComponent(): JComponent = component

    override fun install(statusBar: StatusBar) {
        component.start()
        connection = project.messageBus.connect()
        connection?.subscribe(RouteNavigationListener.TOPIC, RouteNavigationListener { _ ->
            component.onNavigate()
        })
    }

    override fun dispose() {
        connection?.disconnect()
        connection = null
        component.stop()
    }

    companion object {
        const val WIDGET_ID = "Restful-all.Pet"
    }
}

private class PetComponent(private val project: Project) : JComponent() {

    @Volatile private var mood: PetMood = PetMood.IDLE
    @Volatile private var currentFrame: Int = 0
    private var moodHoldUntil: Long = 0L
    private var lastFrameAdvanceAt: Long = 0L
    private val random = java.util.Random()

    /** 主时钟：每 ~33ms 唤起一次（≈30fps），具体推帧速度由 mood.fps 决定。 */
    private val timer = Timer(33) {
        tick()
    }

    init {
        preferredSize = Dimension(30, 24)
        isOpaque = false
        toolTipText = MyMessageBundle.message("pet.tooltip.wrapper", FunMessageProvider.pick("pet.tooltip.idle"))
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                triggerMood(PetMood.CLICK, holdMs = 1200)
                toolTipText = MyMessageBundle.message("pet.tooltip.wrapper", FunMessageProvider.pick("pet.tooltip.happy"))
            }

            override fun mouseEntered(e: MouseEvent) {
                toolTipText = MyMessageBundle.message("pet.tooltip.wrapper", pickTooltip())
            }
        })
    }

    fun start() {
        mood = defaultMood()
        currentFrame = 0
        lastFrameAdvanceAt = System.currentTimeMillis()
        timer.start()
    }

    fun stop() {
        timer.stop()
    }

    fun onNavigate() {
        triggerMood(PetMood.HAPPY, holdMs = 2500)
    }

    private fun triggerMood(next: PetMood, holdMs: Long) {
        mood = next
        currentFrame = 0
        moodHoldUntil = System.currentTimeMillis() + holdMs
        lastFrameAdvanceAt = System.currentTimeMillis()
        repaint()
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val frameDuration = 1000L / mood.fps.coerceAtLeast(1)
        if (now - lastFrameAdvanceAt < frameDuration) return
        lastFrameAdvanceAt = now

        val nextFrame = currentFrame + 1
        if (nextFrame >= mood.frameCount) {
            // 一轮播完
            if (!mood.loops) {
                // 一次性动作播完 → 回到默认 mood
                mood = defaultMood()
                currentFrame = 0
            } else {
                currentFrame = 0
                // hold 时间到了之后允许从 HAPPY/TIRED 回落到默认 IDLE
                if (now > moodHoldUntil && mood != defaultMood() && mood != PetMood.IDLE) {
                    mood = defaultMood()
                }
                // IDLE 状态下偶发触发可爱小动作
                maybeTriggerIdleVariant()
            }
        } else {
            currentFrame = nextFrame
        }
        repaint()
    }

    private fun maybeTriggerIdleVariant() {
        if (mood != PetMood.IDLE) return
        val r = random.nextInt(100)
        when {
            r < 5 -> triggerMood(PetMood.STRETCH, holdMs = 0)
            r < 20 -> triggerMood(PetMood.LICK, holdMs = 0)
        }
    }

    private fun defaultMood(): PetMood {
        return try {
            if (RouteStatsService.getInstance(project).getTodayNavigationCount() > 50) PetMood.TIRED
            else PetMood.IDLE
        } catch (_: Throwable) {
            PetMood.IDLE
        }
    }

    private fun pickTooltip(): String {
        val scene = when (mood) {
            PetMood.TIRED -> "pet.tooltip.tired"
            PetMood.HAPPY, PetMood.CLICK -> "pet.tooltip.happy"
            else -> "pet.tooltip.idle"
        }
        return FunMessageProvider.pick(scene)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            PetRenderer.paint(g2, mood, width, height, currentFrame)
        } finally {
            g2.dispose()
        }
    }
}

/**
 * Status Bar 入口工厂。由 plugin.xml 注册。
 * 通过 [PluginSettingsState] 的开关决定是否出现。
 */
class PetStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = PetStatusBarWidget.WIDGET_ID
    override fun getDisplayName(): String = MyMessageBundle.message("pet.widget.name")
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget {
        val widget = PetStatusBarWidget(project)
        Disposer.register(project, widget)
        return widget
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
