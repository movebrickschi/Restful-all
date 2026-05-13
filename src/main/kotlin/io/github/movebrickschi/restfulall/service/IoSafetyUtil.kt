package io.github.movebrickschi.restfulall.service

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * v1.3.1 - IO 安全与边界工具集。
 *
 * 集中放置以下两类一行逻辑，避免散落到 UI / Service 各处又难以单测：
 *
 * 1. [isInsideDirectory]：拒绝 `..` 与绝对路径形态导致的目录穿越；返回 `target` 是否
 *    位于 `base` 内（含 `base` 自身）。
 * 2. [readBoundedBytes]：流式读取最多 `maxBytes` 字节，超出即停止并标记为 truncated；
 *    避免 `InputStream.readBytes()` 在恶意大响应下 OOM。
 */
object IoSafetyUtil {

    /**
     * 判断 [target] 是否位于 [base] 目录树内（含 [base] 本身）。
     *
     * - 解析为 canonicalPath 后比较，能消解 `..` / 符号链接 / 大小写差异
     * - 任何 IO 异常或权限异常都视为越界（保守拒绝）
     *
     * @param target 待检查路径
     * @param base 期望的根目录
     * @return true = 在 [base] 内（可读）；false = 越界 / 不可访问
     */
    fun isInsideDirectory(target: File, base: File): Boolean = try {
        val baseCanonical = base.canonicalPath
        val targetCanonical = target.canonicalPath
        val sep = File.separator
        val baseWithSep = if (baseCanonical.endsWith(sep)) baseCanonical else baseCanonical + sep
        targetCanonical == baseCanonical || targetCanonical.startsWith(baseWithSep)
    } catch (e: IOException) {
        false
    } catch (e: SecurityException) {
        false
    }

    /**
     * 流式读取 [stream] 至多 [maxBytes] 字节。
     *
     * - 当流长度 ≤ [maxBytes]：返回完整字节，`truncated=false`
     * - 当流长度 > [maxBytes]：返回前 [maxBytes] 字节，`truncated=true`
     *
     * 该方法不会 close [stream]；调用方负责生命周期管理。
     */
    fun readBoundedBytes(stream: InputStream, maxBytes: Int): Pair<ByteArray, Boolean> {
        require(maxBytes >= 0) { "maxBytes must be >= 0" }
        val buf = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024).coerceAtLeast(0))
        val chunk = ByteArray(8 * 1024)
        var total = 0
        var truncated = false
        while (true) {
            val remaining = maxBytes - total
            if (remaining <= 0) {
                if (stream.read() != -1) truncated = true
                break
            }
            val read = stream.read(chunk, 0, minOf(chunk.size, remaining))
            if (read <= 0) break
            buf.write(chunk, 0, read)
            total += read
        }
        return buf.toByteArray() to truncated
    }
}
