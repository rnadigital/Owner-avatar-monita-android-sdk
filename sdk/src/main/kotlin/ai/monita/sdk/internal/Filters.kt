// Copyright RNA Digital PTY LTD
package ai.monita.sdk.internal

/**
 * Filter evaluation ported from the JS checkPassOnFilters and
 * checkPassOnFilterGroups. A legacy flat filter list is an AND of all entries.
 * Filter groups are ORed; within a group, op "all" requires every condition
 * and op "any" requires at least one, each condition evaluated with the same
 * single-filter semantics as the legacy path. Unknown operators pass
 * (forward compatibility, matching JS).
 */
internal fun checkPassOnFilters(data: Any?, filters: List<WireFilter>): Boolean {
    for (f in filters) {
        val filterValues: List<String?> = if (f.values.isNullOrEmpty()) listOf(null) else f.values
        when (f.op) {
            "eq", "contains" -> {
                var result = false
                val value = fillParamsFromData(f.key, data)
                for (filterValue in filterValues) {
                    val hit = if (f.op == "eq") {
                        looseEquals(value, filterValue)
                    } else {
                        value is String && filterValue != null && value.contains(filterValue)
                    }
                    if (hit) {
                        result = true
                        break
                    }
                }
                if (!result) return false
            }
            "ne" -> {
                for (filterValue in filterValues) {
                    if (looseEquals(fillParamsFromData(f.key, data), filterValue)) return false
                }
            }
            "blank" -> {
                val value = fillParamsFromData(f.key, data)
                if (value != null && (value !is String || value.isNotEmpty())) return false
            }
            "not_blank" -> {
                val value = fillParamsFromData(f.key, data)
                if (value == null || (value is String && value.isEmpty())) return false
            }
            "exist" -> {
                if (fillParamsFromData(f.key, data) == null) return false
            }
            "not_exist" -> {
                if (fillParamsFromData(f.key, data) != null) return false
            }
            else -> {
                // Unknown operator: the filter passes, matching the JS reference.
            }
        }
    }
    return true
}

internal fun checkPassOnFilterGroups(data: Any?, groups: List<WireFilterGroup>?): Boolean {
    if (groups.isNullOrEmpty()) return true
    for (g in groups) {
        val filters = g.filters
        if (filters.isEmpty()) continue
        if (g.op == "any") {
            for (f in filters) {
                if (checkPassOnFilters(data, listOf(f))) return true
            }
        } else {
            if (checkPassOnFilters(data, filters)) return true
        }
    }
    return false
}
