// Copyright RNA Digital PTY LTD
package ai.monita.sdk.internal

import kotlin.math.abs
import kotlin.math.floor

/**
 * Dot path and value helpers ported from the Monita JS reference
 * (global-inject-script.ts: getKeys, flatten, multiply, dotAccess).
 * Parsed request data is represented as plain Kotlin values:
 * Map(String, Any?), List(Any?), String, Long, Double, Boolean, null.
 */

/**
 * Splits a dot path into key segments, supporting quoted segments that contain
 * dots: a.b.'c.d' yields [a, b, c.d]. Mirrors the JS getKeys reducer.
 */
internal fun getKeys(keyPath: String): List<String> {
    val arr = keyPath.split(".")
    val acc = mutableListOf<String>()
    var nextI = -1
    for (i in arr.indices) {
        val x = arr[i]
        if (x.startsWith("\"") || x.startsWith("'")) {
            val endChar = x[0]
            if (x.endsWith(endChar) && x.length >= 2) {
                acc.add(x.substring(1, x.length - 1))
            } else if (x.length == 1) {
                acc.add("")
            } else {
                val dt = mutableListOf(x.substring(1))
                var found = false
                for (j in i + 1 until arr.size) {
                    if (arr[j].endsWith(endChar)) {
                        dt.add(arr[j].substring(0, arr[j].length - 1))
                        nextI = j + 1
                        found = true
                        break
                    } else {
                        dt.add(arr[j])
                    }
                }
                if (!found) {
                    acc.add(x)
                } else {
                    acc.add(dt.joinToString("."))
                }
            }
        } else if (nextI != -1) {
            if (nextI == i) {
                acc.add(x)
                nextI = -1
            }
        } else {
            acc.add(x)
        }
    }
    return acc
}

/** Deep flatten: [[a, b], [c, [d]]] becomes [a, b, c, d]. */
internal fun deepFlatten(list: List<Any?>): List<Any?> =
    list.flatMap { item -> if (item is List<*>) deepFlatten(item) else listOf(item) }

/**
 * Renders a value the way JavaScript string concatenation would.
 * Whole doubles print without a fractional part (1.0 prints as "1").
 */
internal fun jsToString(value: Any?): String = when (value) {
    null -> "null"
    is String -> value
    is Boolean -> if (value) "true" else "false"
    is Double ->
        if (value.isFinite() && value == floor(value) && abs(value) < 1e15) value.toLong().toString()
        else value.toString()
    is Map<*, *> -> "[object Object]"
    is List<*> -> value.joinToString(",") { if (it == null) "" else jsToString(it) }
    else -> value.toString()
}

/**
 * Cross-product concatenation: [a, [1, 2], b] becomes [a1b, a2b].
 * Mirrors the JS multiply reducer used for array fan-out in templates.
 */
internal fun multiply(items: List<Any?>): List<String> =
    items.fold(listOf("")) { acc, item ->
        if (item is List<*>) {
            acc.flatMap { prefix -> item.flatMap { element -> multiply(listOf(prefix, element)) } }
        } else {
            acc.map { it + jsToString(item) }
        }
    }

/**
 * Walks a dot path into parsed data. Arrays fan out: a path through an array
 * collects the value from every element, dropping nulls; an empty collection
 * resolves to null. Mirrors the JS dotAccess reducer.
 */
internal fun dotAccess(obj: Any?, keys: List<String>): Any? {
    var current: Any? = obj
    for (k in keys) {
        current = when {
            current is List<*> -> {
                val collected = deepFlatten(current.map { dotAccess(it, listOf(k)) }).filterNotNull()
                if (collected.isNotEmpty()) collected else null
            }
            current is Map<*, *> && current.containsKey(k) -> current[k]
            else -> null
        }
    }
    return current
}

internal fun dotAccess(obj: Any?, keyPath: String): Any? = dotAccess(obj, getKeys(keyPath))

/**
 * JavaScript-style loose falsiness for the getInOrder default test:
 * null, false, 0, "", and strings that numerically coerce to 0 are skipped.
 */
internal fun jsLooselyFalse(value: Any?): Boolean = when (value) {
    null -> true
    is Boolean -> !value
    is Long -> value == 0L
    is Int -> value == 0
    is Double -> value == 0.0
    is String -> value.isEmpty() || value.trim().toDoubleOrNull() == 0.0
    else -> false
}

/**
 * JavaScript-style loose equality between an extracted value and a filter
 * value from the wire (filter values are strings). Numbers compare
 * numerically against numeric strings; booleans coerce to 1 and 0; a list
 * coerces to its comma-joined string form the way a JS array does, so
 * ["Purchase"] equals "Purchase".
 */
internal fun looseEquals(a: Any?, b: Any?): Boolean {
    if (a == null || b == null) return a == null && b == null
    if (a is List<*>) return looseEquals(jsToString(a), b)
    if (a is String && b is String) return a == b
    val an = numericValue(a)
    val bn = numericValue(b)
    if (an != null && bn != null) return an == bn
    return a == b
}

private fun numericValue(v: Any?): Double? = when (v) {
    is Long -> v.toDouble()
    is Int -> v.toDouble()
    is Double -> v
    is Boolean -> if (v) 1.0 else 0.0
    is String -> if (v.isBlank()) 0.0 else v.trim().toDoubleOrNull()
    else -> null
}
