// Copyright RNA Digital PTY LTD
package ai.monita.sdk.internal

/** Maximum number of top level parameters kept per event (mirrors JS MAX_PARAMS_TAKEN). */
internal const val MAX_PARAMS_TAKEN = 100

/**
 * Deletes a value by key path, ported from the JS deleteProperty.
 * Path segments are split on dots; a segment written as /pattern/ is a regex
 * matched against every key at that level. Arrays traverse like JS objects:
 * their keys are the index strings, so numeric and /regex/ segments both
 * address elements. A deleted array element becomes null, mirroring how a
 * JS delete-hole serializes. Deletion never throws.
 */
internal fun deleteProperty(obj: Any?, path: String) {
    try {
        val parts = path.split(".")
        traverseAndDelete(obj, parts, 0)
    } catch (t: Throwable) {
        // Mirror JS: exclusion failures are swallowed, data ships unmodified.
    }
}

private fun traverseAndDelete(current: Any?, parts: List<String>, index: Int) {
    if (index >= parts.size) return
    val part = parts[index]
    val regex: Regex? = if (part.startsWith("/") && part.endsWith("/")) {
        try {
            Regex(part.substring(1, maxOf(1, part.length - 1)))
        } catch (t: Throwable) {
            return
        }
    } else {
        null
    }
    when (current) {
        is MutableMap<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            val map = current as MutableMap<String, Any?>
            val keysToProcess: List<String> =
                if (regex != null) map.keys.filter { regex.containsMatchIn(it) } else listOf(part)
            if (index == parts.size - 1) {
                for (key in keysToProcess) {
                    map.remove(key)
                }
            } else {
                for (key in keysToProcess.toList()) {
                    val value = map[key]
                    if (value != null) {
                        traverseAndDelete(value, parts, index + 1)
                    }
                }
            }
        }
        is MutableList<*> -> {
            @Suppress("UNCHECKED_CAST")
            val list = current as MutableList<Any?>
            val indicesToProcess: List<Int> = if (regex != null) {
                list.indices.filter { regex.containsMatchIn(it.toString()) }
            } else {
                val i = part.toIntOrNull()
                if (i != null && i >= 0 && i < list.size) listOf(i) else emptyList()
            }
            if (index == parts.size - 1) {
                for (i in indicesToProcess) {
                    list[i] = null
                }
            } else {
                for (i in indicesToProcess) {
                    val value = list[i]
                    if (value != null) {
                        traverseAndDelete(value, parts, index + 1)
                    }
                }
            }
        }
        else -> {}
    }
}

/**
 * Builds the outgoing data object for an event: deep copies the first
 * MAX_PARAMS_TAKEN parameters in insertion order, then removes every
 * excluded parameter path. Ported from the JS filterData.
 */
internal fun filterData(
    vendorName: String?,
    data: Map<String, Any?>,
    excludedParams: List<String>,
): MutableMap<String, Any?> {
    return try {
        if (vendorName.isNullOrEmpty()) return LinkedHashMap()
        val newData = LinkedHashMap<String, Any?>()
        var keyCount = MAX_PARAMS_TAKEN
        for ((key, value) in data) {
            keyCount--
            if (keyCount < 0) break
            newData[key] = deepCopyValue(value)
        }
        for (excluded in excludedParams) {
            deleteProperty(newData, excluded)
        }
        newData
    } catch (t: Throwable) {
        LinkedHashMap(data)
    }
}

private fun deepCopyValue(value: Any?): Any? = when (value) {
    is Map<*, *> -> {
        val copy = LinkedHashMap<String, Any?>()
        for ((k, v) in value) {
            copy[k.toString()] = deepCopyValue(v)
        }
        copy
    }
    is List<*> -> value.mapTo(ArrayList()) { deepCopyValue(it) }
    else -> value
}
