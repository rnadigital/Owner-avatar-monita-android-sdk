// Copyright RNA Digital PTY LTD
package ai.monita.sdk.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Bridge between plain Kotlin values (Map, List, String, Long, Double,
 * Boolean, null) and kotlinx.serialization JSON elements. The whole pipeline
 * works on plain values; JSON only appears at the wire boundary.
 */
internal object JsonValue {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        explicitNulls = true
    }

    fun parse(text: String): Any? = fromElement(json.parseToJsonElement(text))

    fun parseOrNull(text: String): Any? = try {
        parse(text)
    } catch (t: Throwable) {
        null
    }

    fun fromElement(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive ->
            if (element.isString) {
                element.content
            } else {
                element.booleanOrNull
                    ?: element.longOrNull
                    ?: element.doubleOrNull
                    ?: element.content
            }
        is JsonArray -> element.mapTo(ArrayList<Any?>(element.size)) { fromElement(it) }
        is JsonObject -> {
            val map = LinkedHashMap<String, Any?>(element.size)
            for ((k, v) in element) {
                map[k] = fromElement(v)
            }
            map
        }
    }

    fun toElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> doubleElement(value)
        is Float -> doubleElement(value.toDouble())
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toElement(v) })
        is List<*> -> JsonArray(value.map { toElement(it) })
        is Array<*> -> JsonArray(value.map { toElement(it) })
        else -> JsonPrimitive(value.toString())
    }

    fun encode(value: Any?): String = json.encodeToString(JsonElement.serializer(), toElement(value))

    /**
     * Doubles serialize in plain decimal notation, never scientific: an
     * epoch-seconds tm must read 1724745600.123, not 1.724745600123E9.
     * Non-finite values become null, matching JSON.stringify.
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun doubleElement(value: Double): JsonElement =
        if (value.isFinite()) {
            kotlinx.serialization.json.JsonUnquotedLiteral(
                java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
            )
        } else {
            JsonNull
        }
}
