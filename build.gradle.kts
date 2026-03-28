plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
}

group = "io.github.movebrickschi"
version = "1.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        intellijIdea("2025.2.4")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here:

        composeUI()

    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
        }

        changeNotes = """
            <h3>1.1 - Feature Update</h3>
            <ul>
                <li><b>API Debug Panel</b>: Full-featured HTTP client supporting GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS with request/response inspection.</li>
                <li><b>Body Types</b>: Support for none, form-data (with file upload), x-www-form-urlencoded, JSON, XML, and raw body types.</li>
                <li><b>Base URL Management</b>: Per-module base URL configuration with auto-detection of ports and context-path from application.properties/yml.</li>
                <li><b>Global Parameters</b>: Configure global Query, Body, Headers, and Cookies that are automatically merged into every request.</li>
                <li><b>JSON Formatting</b>: One-click JSON formatting for global body content and response body.</li>
                <li><b>Request History</b>: Automatically records up to 500 requests grouped by date, with full request/response replay.</li>
                <li><b>Double-click Navigation</b>: Double-click any route in the list to jump to its source code.</li>
                <li><b>Copy Route Path</b>: Press <b>Ctrl+Shift+C</b> anywhere inside a route method to copy the API path to clipboard.</li>
                <li><b>Exclusive Tool Window</b>: Auto-hides other tool windows when activated, and hides itself when switching to other tool windows.</li>
                <li>Requires IntelliJ IDEA 2025.2+ (build 252+).</li>
            </ul>

            <h3>1.1 - 功能更新</h3>
            <ul>
                <li><b>API 调试面板</b>：完整的 HTTP 客户端，支持 GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS，可查看请求和响应详情。</li>
                <li><b>多种 Body 类型</b>：支持 none、form-data（文件上传）、x-www-form-urlencoded、JSON、XML、raw。</li>
                <li><b>前置 URL 管理</b>：按模块配置基础 URL，支持从 application.properties/yml 自动检测端口和 context-path。</li>
                <li><b>全局参数</b>：配置全局 Query、Body、Headers、Cookies，自动合并到每次请求中。</li>
                <li><b>JSON 格式化</b>：一键格式化全局 Body 和响应内容中的 JSON。</li>
                <li><b>请求历史</b>：自动记录最多 500 条请求，按日期分组，支持完整回放。</li>
                <li><b>双击跳转</b>：在路由列表中双击即可跳转到对应源码位置。</li>
                <li><b>复制路由路径</b>：在方法体内任意位置按 <b>Ctrl+Shift+C</b> 即可复制 API 路由路径到剪贴板。</li>
                <li><b>工具窗口互斥</b>：激活时自动隐藏其他工具窗口，切换到其他工具窗口时自动隐藏。</li>
                <li>需要 IntelliJ IDEA 2025.2+（构建版本 252+）。</li>
            </ul>

            <h3>1.0 - Initial Release</h3>
            <ul>
                <li>Support discovering and navigating REST API routes across multiple frameworks (Spring Boot, NestJS, Express.js, FastAPI, Flask).</li>
                <li>Provide route search popup via <b>Ctrl+Alt+/</b> (<b>Cmd+Alt+/</b> on Mac) for quick navigation.</li>
                <li>Add tool window panel for browsing all discovered routes in the project.</li>
                <li>Built with Compose UI for a modern and responsive user interface.</li>
            </ul>

            <h3>1.0 - 初始版本</h3>
            <ul>
                <li>支持发现和导航多种框架的 REST API 路由（Spring Boot、NestJS、Express.js、FastAPI、Flask）。</li>
                <li>提供快捷键 <b>Ctrl+Alt+/</b>（Mac 上为 <b>Cmd+Alt+/</b>）打开路由搜索弹窗，快速跳转。</li>
                <li>新增工具窗口面板，浏览项目中所有已发现的路由。</li>
                <li>基于 Compose UI 构建，提供现代化的响应式用户界面。</li>
            </ul>
        """.trimIndent()
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
