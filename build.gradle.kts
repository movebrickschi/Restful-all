plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
}

group = "io.github.movebrickschi"
version = "1.0"

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
            sinceBuild = "241.19416.15"
        }

        changeNotes = """
            <h3>1.0 - Initial Release</h3>
            <ul>
                <li>Support discovering and navigating REST API routes across multiple frameworks (Spring Boot, NestJS, Express.js, FastAPI, Flask).</li>
                <li>Provide route search popup via <b>Ctrl+Alt+/</b> (<b>Cmd+Alt+/</b> on Mac) for quick navigation.</li>
                <li>Add tool window panel for browsing all discovered routes in the project.</li>
                <li>Built with Compose UI for a modern and responsive user interface.</li>
                <li>Requires IntelliJ IDEA 2025.2.4+ (build 252.25557+).</li>
            </ul>

            <h3>1.0 - 初始版本</h3>
            <ul>
                <li>支持发现和导航多种框架的 REST API 路由（Spring Boot、NestJS、Express.js、FastAPI、Flask）。</li>
                <li>提供快捷键 <b>Ctrl+Alt+/</b>（Mac 上为 <b>Cmd+Alt+/</b>）打开路由搜索弹窗，快速跳转。</li>
                <li>新增工具窗口面板，浏览项目中所有已发现的路由。</li>
                <li>基于 Compose UI 构建，提供现代化的响应式用户界面。</li>
                <li>需要 IntelliJ IDEA 2025.2.4+（构建版本 252.25557+）。</li>
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
