// Copyright RNA Digital PTY LTD
package ai.monita.sdk.internal

/**
 * Request data extraction ported from the JS getBodyJSON, fillDataFromBody
 * and getDataByType. Populates a parameter map from URL query parameters
 * (standard, legacy semicolon separated, and path matrix parameters) and
 * from the request body when it is parseable as JSON, form urlencoded, or
 * plain key=value pairs.
 */
internal object DataParser {

    /** Body read cap: bodies larger than this contribute URL parameters only. */
    const val MAX_BODY_BYTES = 64 * 1024

    private val TEXTUAL_CONTENT_PREFIXES = listOf(
        "application/json", "application/x-www-form-urlencoded", "text/",
        "application/xml", "application/javascript", "application/graphql",
    )

    fun isTextualContentType(contentType: String?): Boolean {
        if (contentType == null) return true
        val normalized = contentType.lowercase().trim()
        return TEXTUAL_CONTENT_PREFIXES.any { normalized.startsWith(it) } ||
            normalized.contains("json") || normalized.contains("urlencoded")
    }

    /**
     * Fills [data] from a request URL's query and matrix parameters and from
     * an optional body string, mirroring the JS getBodyJSON branch order.
     */
    fun getBodyJson(url: String, body: String?, data: MutableMap<String, Any?>, contentTypeHint: String? = null) {
        val query = rawQuery(url)
        val path = rawPath(url)
        if (query.isNotEmpty() && !query.contains("&") && query.split(";").size > 1) {
            for ((key, value) in query.split(";").mapNotNull(::asPair)) {
                data[key] = jsDecodeUriComponent(value)
            }
        } else if (query.isNotEmpty() && query.split("&")[0].isNotEmpty()) {
            for (segment in query.split("&")) {
                if (segment.isEmpty()) continue
                val idx = segment.indexOf('=')
                val rawKey = if (idx >= 0) segment.substring(0, idx) else segment
                val rawValue = if (idx >= 0) segment.substring(idx + 1) else ""
                val key = formDecode(rawKey)
                // The JS reference decodes the value twice: URLSearchParams
                // decoding first, then an explicit decodeURIComponent.
                val once = formDecode(rawValue)
                data[key] = jsDecodeUriComponent(once)
            }
        } else if (path.split(";").size > 1) {
            for ((key, value) in path.split(";").drop(1).mapNotNull(::asPair)) {
                data[key] = jsDecodeUriComponent(value)
            }
        }
        if (body == null) return
        var bodyContent: Any? = body
        val parsedJson = strictJsonParseOrNull(body)
        if (parsedJson != null || body.trim() == "null") {
            bodyContent = parsedJson
        } else if (body.split(";").size > 1) {
            val legacyPairs = body.split(";").mapNotNull(::asPair)
            if (legacyPairs.size > 1) {
                val obj = LinkedHashMap<String, Any?>()
                for ((key, value) in legacyPairs) {
                    obj[key] = jsDecodeUriComponent(value)
                }
                bodyContent = obj
            }
        } else if (body.split("&").size > 1) {
            val pairs = formPairs(body)
            if ((pairs.size == 1 && pairs[0].second != "") || pairs.size > 1) {
                val obj = LinkedHashMap<String, Any?>()
                for ((key, value) in pairs) {
                    obj[key] = jsDecodeUriComponent(value)
                }
                bodyContent = obj
            }
        }
        fillDataFromBody(data, bodyContent, contentTypeHint)
    }

    /** Ported from the JS fillDataFromBody: merges typed body content into [dataObj]. */
    fun fillDataFromBody(dataObj: MutableMap<String, Any?>, body: Any?, contentTypeHint: String? = null) {
        when (val parsed = getDataByType(body, contentTypeHint)) {
            is Typed.Null -> {}
            is Typed.Primitive -> dataObj["value"] = parsed.value
            is Typed.Str -> dataObj["value"] = parsed.value
            is Typed.Arr -> {
                for ((i, item) in parsed.items.withIndex()) {
                    when (item) {
                        is Typed.Null -> dataObj[i.toString()] = null
                        is Typed.Primitive -> dataObj[i.toString()] = item.value
                        is Typed.Str -> dataObj[i.toString()] = item.value
                        is Typed.Obj -> dataObj[i.toString()] = LinkedHashMap(item.value)
                        is Typed.Arr -> dataObj[i.toString()] = item.raw
                    }
                }
            }
            is Typed.Obj -> dataObj.putAll(parsed.value)
        }
    }

    private sealed interface Typed {
        data object Null : Typed
        data class Primitive(val value: Any) : Typed
        data class Str(val value: String) : Typed
        data class Arr(val items: List<Typed>, val raw: List<Any?>) : Typed
        data class Obj(val value: Map<String, Any?>) : Typed
    }

    private fun getDataByType(value: Any?, hintType: String?): Typed = when (value) {
        null -> Typed.Null
        is Boolean, is Long, is Int, is Double -> Typed.Primitive(value)
        is List<*> -> Typed.Arr(value.map { getDataByType(it, hintType) }, value)
        is Map<*, *> -> {
            val map = LinkedHashMap<String, Any?>()
            for ((k, v) in value) {
                map[k.toString()] = v
            }
            Typed.Obj(map)
        }
        is String -> parseStringValue(value, hintType)
        else -> Typed.Str(value.toString())
    }

    private fun parseStringValue(value: String, hintType: String?): Typed {
        if (hintType?.lowercase()?.startsWith("application/json") == true ||
            value.startsWith("{") || value.startsWith("[")
        ) {
            val parsed = strictJsonParseOrNull(value)
            when (parsed) {
                is Map<*, *>, is List<*> -> return getDataByType(parsed, hintType)
                else -> {}
            }
        }
        val pairs = formPairs(value)
        if (pairs.size == 1 && pairs[0].second == "" && pairs[0].first == value) {
            return Typed.Str(value)
        }
        if (pairs.isNotEmpty()) {
            val obj = LinkedHashMap<String, Any?>()
            for ((key, v) in pairs) {
                obj[key] = v
            }
            return Typed.Obj(obj)
        }
        return Typed.Str(value)
    }

    // -- low level helpers ---------------------------------------------------

    private val strictJson = kotlinx.serialization.json.Json { isLenient = false }

    /** Strict JSON parse, matching the JS JSON.parse used for bodies. */
    internal fun strictJsonParseOrNull(text: String): Any? = try {
        val value = JsonValue.fromElement(strictJson.parseToJsonElement(text))
        // kotlinx tokenizes bare words as unquoted strings even in strict
        // mode; JSON.parse rejects them, so only quoted strings count.
        if (value is String && !text.trim().startsWith("\"")) null else value
    } catch (t: Throwable) {
        null
    }

    internal fun rawQuery(url: String): String {
        val noFragment = url.substringBefore('#')
        val q = noFragment.indexOf('?')
        return if (q >= 0) noFragment.substring(q + 1) else ""
    }

    internal fun rawPath(url: String): String {
        var s = url.substringBefore('#').substringBefore('?')
        val schemeIdx = s.indexOf("://")
        if (schemeIdx >= 0) {
            s = s.substring(schemeIdx + 3)
            val slash = s.indexOf('/')
            s = if (slash >= 0) s.substring(slash) else ""
        }
        return s
    }

    private fun asPair(segment: String): Pair<String, String>? {
        val parts = segment.split("=")
        return if (parts.size == 2) parts[0] to parts[1] else null
    }

    /** URLSearchParams style pair parsing: plus signs and percent escapes decode. */
    internal fun formPairs(input: String): List<Pair<String, String>> {
        if (input.isEmpty()) return emptyList()
        return input.split("&").filter { it.isNotEmpty() }.map { segment ->
            val idx = segment.indexOf('=')
            if (idx >= 0) {
                formDecode(segment.substring(0, idx)) to formDecode(segment.substring(idx + 1))
            } else {
                formDecode(segment) to ""
            }
        }
    }

    /** Form decoding: '+' becomes a space, then percent escapes decode. */
    internal fun formDecode(input: String): String = percentDecode(input.replace('+', ' ')) ?: input

    /**
     * decodeURIComponent equivalent: percent escapes decode, '+' is preserved.
     * A malformed escape or an escape sequence that is not valid UTF-8
     * returns the input unchanged. This is a deliberate deviation from the
     * JS reference, where decodeURIComponent throws and the whole event is
     * dropped; keeping the raw value loses less data.
     */
    internal fun jsDecodeUriComponent(input: String): String = percentDecode(input) ?: input

    private fun percentDecode(input: String): String? {
        if (!input.contains('%')) return input
        val out = java.io.ByteArrayOutputStream(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '%') {
                if (i + 2 >= input.length) return null
                val hex = input.substring(i + 1, i + 3)
                val byte = hex.toIntOrNull(16) ?: return null
                out.write(byte)
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i += 1
            }
        }
        val decoded = String(out.toByteArray(), Charsets.UTF_8)
        // Invalid UTF-8 decodes to replacement characters; treat that as a
        // failed decode (unless the input already contained one) so the
        // caller falls back to the raw value instead of mangled text.
        if (decoded.contains('�') && !input.contains('�')) return null
        return decoded
    }
}
