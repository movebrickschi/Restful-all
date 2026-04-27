import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
}

group = "io.github.movebrickschi"
version = "1.2.1"

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
            <h3>1.2.1 - UI Polish & Document Export</h3>
            <ul>
                <li><b>UI Layout Optimization</b>: Refined overall panel and popup layouts for better visual hierarchy, consistent spacing, and improved readability across light and dark themes.</li>
                <li><b>Export Document</b>: Added one-click document export that writes the current interface/route definitions to a file on disk, supporting Markdown and other common formats.</li>
            </ul>

            <h3>1.2.1 - UI 优化与文档导出</h3>
            <ul>
                <li><b>优化 UI 布局</b>：全面调整面板与弹窗的视觉层级、间距及可读性，亮色与暗色主题下均保持一致的视觉体验。</li>
                <li><b>导出文档功能</b>：新增一键导出，将当前接口 / 路由定义写入到磁盘文件，支持 Markdown 等常用格式。</li>
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
