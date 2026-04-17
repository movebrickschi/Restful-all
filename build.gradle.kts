plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
}

group = "io.github.movebrickschi"
version = "1.1.1"

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
        bundledPlugin("com.intellij.java")

        composeUI()

    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
        }

        changeNotes = """
            <h3>1.1.1 - Feature Update</h3>
            <ul>
                <li><b>JSON Formatting</b>: One-click JSON formatting for global body content and response body.</li>
                <li><b>Request History</b>: Automatically records up to 500 requests grouped by date, with full request/response replay.</li>
                <li><b>Double-click Navigation</b>: Double-click any route in the list to jump to its source code.</li>
                <li><b>Copy Route Path</b>: Press <b>Ctrl+Shift+C</b> anywhere inside a route method to copy the API path to clipboard.</li>
                <li><b>Exclusive Tool Window</b>: Auto-hides other tool windows when activated, and hides itself when switching to other tool windows.</li>
                <li>Requires IntelliJ IDEA 2025.2+ (build 252+).</li>
            </ul>

            <h3>1.1.1 - 功能更新</h3>
            <ul>
                <li><b>JSON 格式化</b>：一键格式化全局 Body 和响应内容中的 JSON。</li>
                <li><b>请求历史</b>：自动记录最多 500 条请求，按日期分组，支持完整回放。</li>
                <li><b>双击跳转</b>：在路由列表中双击即可跳转到对应源码位置。</li>
                <li><b>复制路由路径</b>：在方法体内任意位置按 <b>Ctrl+Shift+C</b> 即可复制 API 路由路径到剪贴板。</li>
                <li><b>工具窗口互斥</b>：激活时自动隐藏其他工具窗口，切换到其他工具窗口时自动隐藏。</li>
                <li>需要 IntelliJ IDEA 2025.2+（构建版本 252+）。</li>
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
