package io.github.movebrickschi.restfulall.service

/**
 * v1.3 F2 - cURL 双向转换。
 *
 * 将 `curl ...` 命令解析为 [RequestSpec]，反之将 [RequestSpec] 导出为 cURL 命令。
 *
 * 支持的 cURL 参数：
 * `-X / --request` `-H / --header` `-d / --data / --data-raw / --data-binary`
 * `-F / --form` `-u / --user` `-b / --cookie` `--compressed` `--insecure`
 */
object CurlConverter {

    /**
     * 解析 cURL 命令字符串为 [RequestSpec]。
     *
     * @throws CurlParseException 解析失败（附失败位置）
     */
    fun parse(curl: String): RequestSpec {
        val tokens = tokenize(curl.trim())
        if (tokens.isEmpty() || tokens[0].lowercase() != "curl") {
            throw CurlParseException("Input must start with 'curl'", 0)
        }

        var method = "GET"
        var url = ""
        val headers = mutableListOf<Pair<String, String>>()
        val cookies = mutableListOf<Pair<String, String>>()
        var bodyContent = ""
        var bodyType = "none"

        var i = 1
        while (i < tokens.size) {
            val token = tokens[i]
            when {
                token in listOf("-X", "--request") -> {
                    i++; method = tokens.getOrElse(i) { "GET" }.uppercase()
                }
                token in listOf("-H", "--header") -> {
                    i++; val headerStr = tokens.getOrElse(i) { "" }
                    val sep = headerStr.indexOf(':')
                    if (sep > 0) {
                        headers.add(headerStr.substring(0, sep).trim() to headerStr.substring(sep + 1).trim())
                    }
                }
                token in listOf("-d", "--data", "--data-raw", "--data-binary") -> {
                    i++; bodyContent = tokens.getOrElse(i) { "" }
                    if (method == "GET") method = "POST"
                    bodyType = inferBodyType(bodyContent, headers)
                }
                token in listOf("-F", "--form") -> {
                    i++
                    if (method == "GET") method = "POST"
                    bodyType = "form-data"
                }
                token in listOf("-u", "--user") -> {
                    i++; val userPass = tokens.getOrElse(i) { "" }
                    val encoded = java.util.Base64.getEncoder().encodeToString(userPass.toByteArray())
                    headers.add("Authorization" to "Basic $encoded")
                }
                token in listOf("-b", "--cookie") -> {
                    i++; val cookieStr = tokens.getOrElse(i) { "" }
                    for (part in cookieStr.split(";")) {
                        val eq = part.indexOf('=')
                        if (eq > 0) cookies.add(part.substring(0, eq).trim() to part.substring(eq + 1).trim())
                    }
                }
                token == "--compressed" -> { /* OkHttp / java.net.http handles this */ }
                token == "--insecure" || token == "-k" -> { /* skip TLS verify setting */ }
                !token.startsWith("-") && url.isEmpty() -> {
                    url = token.removeSurrounding("'").removeSurrounding("\"")
                }
                else -> { /* skip unknown flags */ }
            }
            i++
        }

        if (url.isEmpty()) throw CurlParseException("No URL found in cURL command", 0)

        return RequestSpec(
            method = method,
            url = url,
            headers = headers,
            cookies = cookies,
            bodyType = bodyType,
            bodyContent = bodyContent,
        )
    }

    /**
     * 将 [RequestSpec] 导出为 cURL 命令字符串。
     */
    fun export(spec: RequestSpec): String {
        val parts = mutableListOf("curl")

        if (spec.method != "GET") {
            parts.add("-X"); parts.add(spec.method)
        }

        var url = spec.url
        if (spec.queryParams.isNotEmpty()) {
            val qs = spec.queryParams.joinToString("&") { (k, v) ->
                "${java.net.URLEncoder.encode(k, Charsets.UTF_8)}=${java.net.URLEncoder.encode(v, Charsets.UTF_8)}"
            }
            url = if ("?" in url) "$url&$qs" else "$url?$qs"
        }
        parts.add("'$url'")

        for ((name, value) in spec.headers) {
            parts.add("-H"); parts.add("'$name: $value'")
        }
        for ((name, value) in spec.cookies) {
            parts.add("-b"); parts.add("'$name=$value'")
        }
        if (spec.bodyContent.isNotBlank() && spec.bodyType in listOf("json", "xml", "raw", "x-www-form-urlencoded")) {
            parts.add("-d"); parts.add("'${spec.bodyContent.replace("'", "'\\''")}'")
        }

        return parts.joinToString(" ")
    }

    private fun inferBodyType(body: String, headers: List<Pair<String, String>>): String {
        val ct = headers.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second?.lowercase()
        return when {
            ct?.contains("json") == true -> "json"
            ct?.contains("xml") == true -> "xml"
            ct?.contains("form-urlencoded") == true -> "x-www-form-urlencoded"
            body.trimStart().startsWith("{") || body.trimStart().startsWith("[") -> "json"
            body.trimStart().startsWith("<") -> "xml"
            else -> "raw"
        }
    }

    internal fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var inSingle = false
        var inDouble = false
        var escaped = false

        for (ch in input) {
            when {
                escaped -> { sb.append(ch); escaped = false }
                ch == '\\' && !inSingle -> escaped = true
                ch == '\'' && !inDouble -> inSingle = !inSingle
                ch == '"' && !inSingle -> inDouble = !inDouble
                ch.isWhitespace() && !inSingle && !inDouble -> {
                    if (sb.isNotEmpty()) { tokens.add(sb.toString()); sb.clear() }
                }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) tokens.add(sb.toString())
        return tokens
    }

    class CurlParseException(message: String, val position: Int) : RuntimeException(message)
}
