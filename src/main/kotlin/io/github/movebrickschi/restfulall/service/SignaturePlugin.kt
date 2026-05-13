package io.github.movebrickschi.restfulall.service

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import java.util.TreeMap
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.and

/**
 * v1.3.3 P2-11 - 签名插件机制（Pro）。
 *
 * 提供 AWS Signature V4 与 OAuth 1.0a 两套 in-house 实现；自定义脚本（GraalJS 沙箱）作为
 * 占位 API，调用 [customScript] 会抛 [UnsupportedOperationException]，待后续 v1.3.3.x 接入。
 *
 * 全部算法**无外部依赖**：仅用 JDK `javax.crypto` + `java.security`。
 *
 * ## AWS Signature V4
 *
 * 严格按官方文档 §4：CanonicalRequest → StringToSign → SigningKey → Signature。
 * 输出 `Authorization` header + `x-amz-date` + `x-amz-content-sha256`。
 *
 * ## OAuth 1.0a
 *
 * HMAC-SHA1 签名，输出标准 `Authorization: OAuth ...` header。
 */
object SignaturePlugin {

    data class AwsV4Input(
        val accessKey: String,
        val secretKey: String,
        val region: String,
        val service: String,
        val method: String,
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
    )

    data class AwsV4Output(
        val authorization: String,
        val amzDate: String,
        val contentSha256: String,
    )

    /**
     * 计算 AWS Signature V4。返回三个 header，调用方应将它们叠加到请求 headers 上。
     */
    fun awsV4(input: AwsV4Input): AwsV4Output {
        val uri = URI.create(input.url)
        val now = Instant.now()
        val amzDate = formatAmzDate(now)
        val dateStamp = amzDate.substring(0, 8)
        val payloadHash = hexSha256(input.body)
        val canonicalHeaders = LinkedHashMap<String, String>()
        canonicalHeaders["host"] = uri.host
        canonicalHeaders["x-amz-content-sha256"] = payloadHash
        canonicalHeaders["x-amz-date"] = amzDate
        for ((k, v) in input.headers) {
            val key = k.lowercase().trim()
            if (key.isNotEmpty()) canonicalHeaders[key] = v.trim()
        }
        val sorted = TreeMap(canonicalHeaders)
        val canonicalHeadersStr = sorted.entries.joinToString("") { "${it.key}:${it.value}\n" }
        val signedHeaders = sorted.keys.joinToString(";")

        val canonicalQuery = canonicalQuery(uri.rawQuery.orEmpty())
        val canonicalPath = canonicalPath(uri.rawPath.orEmpty().ifEmpty { "/" })
        val canonicalRequest = buildString {
            appendLine(input.method.uppercase())
            appendLine(canonicalPath)
            appendLine(canonicalQuery)
            append(canonicalHeadersStr)
            appendLine()
            appendLine(signedHeaders)
            append(payloadHash)
        }

        val credentialScope = "$dateStamp/${input.region}/${input.service}/aws4_request"
        val stringToSign = buildString {
            appendLine("AWS4-HMAC-SHA256")
            appendLine(amzDate)
            appendLine(credentialScope)
            append(hexSha256(canonicalRequest.toByteArray(StandardCharsets.UTF_8)))
        }

        val kSecret = ("AWS4" + input.secretKey).toByteArray(StandardCharsets.UTF_8)
        val kDate = hmacSha256(kSecret, dateStamp)
        val kRegion = hmacSha256(kDate, input.region)
        val kService = hmacSha256(kRegion, input.service)
        val kSigning = hmacSha256(kService, "aws4_request")
        val signature = hexEncode(hmacSha256(kSigning, stringToSign))

        val authHeader = "AWS4-HMAC-SHA256 " +
            "Credential=${input.accessKey}/$credentialScope, " +
            "SignedHeaders=$signedHeaders, " +
            "Signature=$signature"

        return AwsV4Output(
            authorization = authHeader,
            amzDate = amzDate,
            contentSha256 = payloadHash,
        )
    }

    data class OAuth1Input(
        val consumerKey: String,
        val consumerSecret: String,
        val token: String = "",
        val tokenSecret: String = "",
        val method: String,
        val url: String,
        val formParams: Map<String, String> = emptyMap(),
    )

    /**
     * 计算 OAuth 1.0a 签名头。返回 `OAuth ...` 字符串，调用方写入 `Authorization` header。
     */
    fun oauth1(input: OAuth1Input): String {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val uri = URI.create(input.url)
        val baseUrl = "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}${uri.rawPath.orEmpty()}"

        val oauthParams = sortedMapOf(
            "oauth_consumer_key" to input.consumerKey,
            "oauth_nonce" to nonce,
            "oauth_signature_method" to "HMAC-SHA1",
            "oauth_timestamp" to timestamp,
            "oauth_version" to "1.0",
        )
        if (input.token.isNotBlank()) oauthParams["oauth_token"] = input.token

        val queryParams = parseQueryString(uri.rawQuery.orEmpty())
        val allParams = TreeMap<String, MutableList<String>>()
        for ((k, v) in oauthParams + queryParams + input.formParams) {
            allParams.getOrPut(percentEncode(k)) { mutableListOf() }.add(percentEncode(v))
        }
        for ((_, vs) in allParams) vs.sort()

        val paramString = allParams.entries.joinToString("&") { (k, vs) ->
            vs.joinToString("&") { "$k=$it" }
        }
        val baseString = "${input.method.uppercase()}&${percentEncode(baseUrl)}&${percentEncode(paramString)}"
        val signingKey = "${percentEncode(input.consumerSecret)}&${percentEncode(input.tokenSecret)}"
        val signature = Base64.getEncoder().encodeToString(hmacSha1(signingKey.toByteArray(StandardCharsets.UTF_8), baseString))

        val finalParams = LinkedHashMap(oauthParams)
        finalParams["oauth_signature"] = signature
        return "OAuth " + finalParams.entries.joinToString(", ") { (k, v) -> "$k=\"${percentEncode(v)}\"" }
    }

    fun customScript(@Suppress("UNUSED_PARAMETER") script: String): Nothing {
        throw UnsupportedOperationException(
            "Custom signature scripts require GraalJS sandbox; deferred to v1.3.3.x. " +
                "Use AWS V4 / OAuth1 built-ins for now.",
        )
    }

    // ---------- helpers ----------

    private fun formatAmzDate(now: Instant): String {
        val sdf = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date(now.toEpochMilli()))
    }

    private fun hexSha256(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return hexEncode(md.digest(data))
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
    }

    private fun hmacSha1(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
    }

    private fun hexEncode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = (b and 0xFF.toByte()).toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private fun canonicalQuery(rawQuery: String): String {
        if (rawQuery.isBlank()) return ""
        val pairs = mutableListOf<Pair<String, String>>()
        for (pair in rawQuery.split("&")) {
            val eq = pair.indexOf('=')
            val k = if (eq >= 0) pair.substring(0, eq) else pair
            val v = if (eq >= 0) pair.substring(eq + 1) else ""
            pairs.add(percentEncode(URLDecodeReserveSafe(k)) to percentEncode(URLDecodeReserveSafe(v)))
        }
        return pairs.sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { "${it.first}=${it.second}" }
    }

    private fun canonicalPath(rawPath: String): String {
        // AWS V4 § 4：path 段需经 RFC3986 编码，但保留 '/'
        return rawPath.split("/").joinToString("/") { percentEncodeKeepSlash(it) }.ifEmpty { "/" }
    }

    private fun percentEncode(value: String): String {
        val encoded = URLEncoder.encode(value, StandardCharsets.UTF_8)
        return encoded
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")
    }

    private fun percentEncodeKeepSlash(segment: String): String = percentEncode(segment)

    private fun URLDecodeReserveSafe(input: String): String =
        try { java.net.URLDecoder.decode(input, "UTF-8") } catch (_: Exception) { input }

    private fun parseQueryString(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (pair in query.split("&")) {
            val eq = pair.indexOf('=')
            val k = if (eq >= 0) pair.substring(0, eq) else pair
            val v = if (eq >= 0) pair.substring(eq + 1) else ""
            map[URLDecodeReserveSafe(k)] = URLDecodeReserveSafe(v)
        }
        return map
    }
}
