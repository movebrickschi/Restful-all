import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
}

group = "io.github.movebrickschi"
version = "1.2.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdea("2025.2.4")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here:
        bundledPlugin("com.intellij.java")

        composeUI()

    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
        }

        productDescriptor {
            code = "PRESTFULALL"
            releaseDate = "20260425"
            releaseVersion = "12"
            optional = true
        }

        changeNotes = """
            <h3>1.2.0 - Discoverability & Cartoon Pet</h3>
            <ul>
                <li><b>Visible Action Toolbar</b>: The route search popup now shows four icon buttons above the list — favorite, pin, edit note, and a one-click <code>@fav</code> filter — plus a grey hint line listing right-click / Ctrl+F / Ctrl+N / @fav / @note shortcuts. Buttons stay disabled until a row is selected.</li>
                <li><b>Pro Marketplace Banner</b>: The popup now displays a small Marketplace Pro banner for unlicensed users, pointing them to the official 30-day trial or license activation.</li>
                <li><b>Scanning Copy</b>: When refreshing routes, the status bar shows a friendly loading line (<code>loading.scanning</code> / <code>loading.big_project</code>) instead of staying blank.</li>
                <li><b>Navigation Cheers</b>: Every successful jump now flashes a short cheer message in the IDE status bar (<code>nav.cheer.*</code>).</li>
                <li><b>Year Report Teaser</b>: After every 100 / 500 / 1000 navigations, a notification invites you to open the annual report; <code>lastTeaserAt</code> prevents repeat alerts.</li>
                <li><b>Cartoon Pixel Cat</b>: The status-bar pet has been redrawn from the "FREE Cat 2D Pixel Art" sprite sheets — now with idle, run, jump, attack and walk frames, plus random idle variants (lick / stretch) and click bounces.</li>
                <li><b>Route Test Gutter Icon</b>: A Pro web icon now appears in the editor gutter beside every REST route definition. Click it to activate the Restful-all tool window, switch to the Interface tab, and auto-load the route into the API debug panel (no request is sent).</li>
                <li><b>Multi-language Gutter Coverage</b>: Gutter entry supports Java / Kotlin (Spring Boot) out of the box; JavaScript / TypeScript / TSX (NestJS, Express) and Python (FastAPI, Flask) are wired via optional plugin dependencies and auto-enable when the corresponding language plugin is installed — no extra config.</li>
                <li><b>Stable Tool Window Handle</b>: Introduced a project-level <code>RestfulToolWindowHolder</code> so external entries (gutter, future actions) can reliably reach the active panel and tab without reflection.</li>
            </ul>

            <h3>1.2.0 - 可见性与卡通小猫</h3>
            <ul>
                <li><b>显式操作工具条</b>：路由搜索弹窗顶部新增 4 个图标按钮（收藏 / 置顶 / 备注 / 一键 @fav 过滤）和一行灰色快捷键提示，让原本只藏在右键和 Ctrl+F/N 后面的功能一眼可见；未选中行时按钮自动灰显。</li>
                <li><b>Pro Marketplace 横幅</b>：弹窗右上角会向未授权用户展示 Marketplace Pro 提示，引导开启官方 30 天试用或激活许可证。</li>
                <li><b>扫描文案</b>：刷新路由时，状态栏会显示一句拟人化的扫描文案（<code>loading.scanning</code> / <code>loading.big_project</code>），替代之前的空白等待。</li>
                <li><b>跳转欢呼</b>：每次成功跳转后，IDE 底部状态栏会闪一句鼓励文案（<code>nav.cheer.*</code>）。</li>
                <li><b>年报里程碑提醒</b>：累计跳转跨过 100 / 500 / 1000 次时，弹出带「打开年度报告」按钮的通知；通过 <code>lastTeaserAt</code> 字段避免重复打扰。</li>
                <li><b>卡通像素小猫</b>：状态栏宠物卡通形象，包含 idle / run / jump / attack / walk 多套动作，会随机做出舔毛、伸懒腰等小动作，点击还会跳一下。</li>
                <li><b>路由测试 Gutter 图标</b>：Pro 用户在业务代码中每个 REST 路由定义的左侧 gutter 会看到一个 web 小图标，点击即可激活 Restful-all 工具窗口、切到「接口」Tab，并把该路由自动加载到 API 调试面板（仅加载，不发送请求）。</li>
                <li><b>多语言 Gutter 覆盖</b>：gutter 入口默认支持 Java / Kotlin（Spring Boot）；JavaScript / TypeScript / TSX（NestJS、Express）与 Python（FastAPI、Flask）通过 optional plugin 依赖接入，IDE 安装对应语言插件后自动启用，无需额外配置。</li>
                <li><b>稳定工具窗口句柄</b>：新增项目级 <code>RestfulToolWindowHolder</code>，让 gutter 等外部入口能稳定拿到当前面板与 Tab，无需反射 ContentManager。</li>
            </ul>

        """.trimIndent()
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2025.2.4")
        }
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
