package io.github.movebrickschi.restfulall.model

import com.intellij.openapi.vfs.VirtualFile
import java.awt.Color

data class RouteInfo(
    val method: HttpMethod,
    val fullPath: String,
    val className: String,
    val functionName: String,
    val file: VirtualFile,
    val lineNumber: Int,
    val framework: Framework,
    val packageName: String = "",
    val routeGroupName: String = "",
    val routeName: String = "",
    /**
     * 路由方法上的文档注释纯文本（KDoc / Javadoc / JSDoc / Python docstring 抽取后清洗），
     * 用于在调试面板展示。`null` 表示未抽取到，UI 应隐藏说明栏；空串视同 null。
     * 与 [routeName]（来自 @Operation.summary 等注解的一句话标题）正交：
     * routeName 优先做列表显示，description 用于详情展开。
     */
    val description: String? = null,
) {
    val displayPath: String = run {
        val normalized = fullPath.replace(SLASH_PATTERN, "/")
        if (normalized.startsWith("/")) normalized else "/$normalized"
    }

    val searchKey: String =
        "${method.displayName} $displayPath $className $functionName $packageName $routeGroupName $routeName".lowercase()

    val displayText: String
        get() = "${method.displayName.padEnd(7)} $displayPath"

    val locationText: String
        get() = "$className#$functionName  (${file.name}:${lineNumber + 1})"

    /**
     * 排序 / 去重时的稳定 dedup key。每条 RouteInfo 只算一次，
     * 避免在 `distinctBy { ... }` 比较器内反复拼字符串导致 GC 压力。
     * 与 [searchKey] 不同：dedupKey 用于身份比较（不进入搜索匹配）。
     */
    val dedupKey: String by lazy {
        "${method}:${displayPath}:${file.path}:$lineNumber"
    }

    companion object {
        private val SLASH_PATTERN = Regex("/+")
    }
}

enum class HttpMethod(val displayName: String, val color: Color) {
    GET("GET", Color(0x61, 0xAF, 0xEF)),
    POST("POST", Color(0x98, 0xC3, 0x79)),
    PUT("PUT", Color(0xE5, 0xC0, 0x7B)),
    DELETE("DELETE", Color(0xE0, 0x6C, 0x75)),
    PATCH("PATCH", Color(0xC6, 0x78, 0xDD)),
    HEAD("HEAD", Color(0x56, 0xB6, 0xC2)),
    OPTIONS("OPTIONS", Color(0xAB, 0xB2, 0xBF)),
    ALL("ALL", Color(0xD1, 0x9A, 0x66));

    companion object {
        fun fromString(value: String): HttpMethod? =
            entries.find { it.name.equals(value, ignoreCase = true) }
    }
}

enum class Framework(val displayName: String) {
    NESTJS("NestJS"),
    SPRING("Spring"),
    EXPRESS("Express"),
    PYTHON("Python"),

    /**
     * F4: OpenAPI 3.x 文件导入而来的虚拟 route。
     *
     * - 不来自任何源码扫描器；由 [io.github.movebrickschi.restfulall.service.SwaggerImporter] 产出
     * - `RouteInfo.file` 为 `com.intellij.testFramework.LightVirtualFile`（IDE 内存中虚拟文件）
     * - `RouteInfo.lineNumber` 仅作为同一虚拟文件内 operation 的去重序号，不对应真实行号
     * - 参数提取走 [io.github.movebrickschi.restfulall.service.OpenApiParamExtractor]，
     *   该 extractor 从 [io.github.movebrickschi.restfulall.service.RouteService] 缓存中按
     *   `RouteInfo.stableId` 直接取已解析好的 `ExtractedMethodParams`
     */
    OPENAPI("OpenAPI"),
}
