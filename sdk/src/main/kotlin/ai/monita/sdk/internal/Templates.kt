// Copyright RNA Digital PTY LTD
package ai.monita.sdk.internal

import java.util.regex.Pattern

/** Maximum input length fed to a template regex (ReDoS guard, mirrors JS). */
private const val REGEX_INPUT_CAP = 4096

/**
 * Event extraction templating, ported from the JS fillParamsFromData.
 *
 * Supports:
 *  - "{{param}}" interpolation with dot path access (quoted segments allowed),
 *  - array fan-out: an array-valued path yields one output per element,
 *  - "{{regex::pattern::field}}" with a 4KB input guard; capture group 1 wins
 *    when the pattern has one, else the whole match; no match or a bad pattern
 *    contributes nothing (never a raw substring),
 *  - a template with no braces is treated as a plain dot path lookup.
 *
 * Returns a String for a single resolved value, a List for a fan-out,
 * or the raw looked-up value for the no-braces path form.
 */
internal fun fillParamsFromData(template: String?, data: Any?): Any? {
    if (template.isNullOrEmpty()) return template
    if (!template.contains("{{") && !template.contains("}}")) {
        return dotAccess(data, template)
    }
    val output = mutableListOf<Any?>()
    var part = StringBuilder()
    var i = 0
    val len = template.length
    while (i < len) {
        val c = template[i]
        if (i < len - 1 && c == '{' && template[i + 1] == '{') {
            if (part.isNotEmpty()) {
                output.add(part.toString())
                part = StringBuilder()
            }
            val parameter = StringBuilder()
            var closed = false
            i += 2
            while (i < len) {
                val pc = template[i]
                if (i < len - 1 && pc == '}' && template[i + 1] == '}') {
                    i += 1
                    if (parameter.isNotEmpty()) {
                        val value = resolveParameter(parameter.toString(), data)
                        if (value != null) {
                            output.add(value)
                        }
                        closed = true
                    }
                    break
                } else {
                    parameter.append(pc)
                }
                i++
            }
            if (!closed && parameter.isNotEmpty()) {
                output.add(parameter.toString())
            }
        } else {
            part.append(c)
        }
        i++
    }
    if (part.isNotEmpty()) {
        output.add(part.toString())
    }
    val multiplied = multiply(output)
    return if (multiplied.size == 1) multiplied[0] else multiplied
}

private fun resolveParameter(parameter: String, data: Any?): Any? {
    val functionSplit = parameter.split("::")
    if (functionSplit.size == 3) {
        val (fn, params, field) = functionSplit
        var value = dotAccess(data, field)
        if (value is String && value.isNotEmpty()) {
            when (fn) {
                "regex" -> {
                    value = try {
                        val input = if (value.length > REGEX_INPUT_CAP) value.substring(0, REGEX_INPUT_CAP) else value
                        val matcher = Pattern.compile(params).matcher(input)
                        if (matcher.find()) {
                            val group1 = if (matcher.groupCount() >= 1) matcher.group(1) else null
                            group1 ?: matcher.group(0)
                        } else {
                            null
                        }
                    } catch (t: Throwable) {
                        null
                    }
                }
                else -> {}
            }
        }
        return value
    }
    return dotAccess(data, parameter)
}
