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
}
