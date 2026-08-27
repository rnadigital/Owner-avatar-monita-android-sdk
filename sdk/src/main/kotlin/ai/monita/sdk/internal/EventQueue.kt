// Copyright RNA Digital PTY LTD
package ai.monita.sdk.internal

import java.io.File

/**
 * Persistent event queue backed by a JSON lines file. Each record carries the
 * batch shared context ("sh") and the per event fields ("ev"). A record is
 * removed only after the caller acknowledges a successful upload (HTTP 2xx).
 * Caps: [maxEvents] records or [maxBytes] serialized bytes, drop oldest.
 */
internal class EventQueue(
    directory: File,
    private val maxEvents: Int = 500,
    private val maxBytes: Long = 2L * 1024 * 1024,
) {

    internal data class Record(
        val id: Long,
        val shared: Map<String, Any?>,
        val event: Map<String, Any?>,
        val line: String,
    )

    private val file = File(directory, "queue.jsonl")
    private val records = ArrayDeque<Record>()
    private var nextId = 1L
    private var byteSize = 0L

    init {
        try {
            directory.mkdirs()
            if (file.exists()) {
                val raw = file.readText()
                var content = raw
                if (content.isNotEmpty() && !content.endsWith("\n")) {
                    // A crash mid-append leaves a partial record on the tail;
                    // truncate it so the rest of the queue stays readable.
                    val lastNewline = content.lastIndexOf('\n')
                    MonitaLog.error("Queue file ended mid-record; dropped the partial tail from an interrupted write")
                    content = if (lastNewline >= 0) content.substring(0, lastNewline + 1) else ""
                }
                var skipped = 0
                for ((lineNumber, line) in content.split("\n").withIndex()) {
                    if (line.isBlank()) continue
                    val record = parseRecord(line)
                    if (record == null) {
                        skipped++
                        MonitaLog.error("Skipped unparseable queue record at line ${lineNumber + 1}")
                        continue
                    }
                    records.addLast(record)
                    byteSize += line.length + 1
                    if (record.id >= nextId) nextId = record.id + 1
                }
                if (content != raw || skipped > 0) {
                    rewrite()
                }
            }
        } catch (t: Throwable) {
            MonitaLog.error("Failed to load queued events", t)
            records.clear()
            byteSize = 0
        }
    }

    private fun parseRecord(line: String): Record? {
        val parsed = JsonValue.parseOrNull(line) as? Map<*, *> ?: return null
        val id = parsed["id"] as? Long ?: return null
        @Suppress("UNCHECKED_CAST")
        val shared = parsed["sh"] as? Map<String, Any?> ?: return null
        @Suppress("UNCHECKED_CAST")
        val event = parsed["ev"] as? Map<String, Any?> ?: return null
        return Record(id, shared, event, line)
    }

    @get:Synchronized
    val size: Int
        get() = records.size

    @Synchronized
    fun snapshot(): List<Record> = records.toList()

    @Synchronized
    fun append(shared: Map<String, Any?>, event: Map<String, Any?>): Boolean {
        return try {
            val id = nextId++
            val line = JsonValue.encode(
                linkedMapOf<String, Any?>("id" to id, "sh" to shared, "ev" to event)
            )
            var dropped = false
            while (records.size + 1 > maxEvents || byteSize + line.length + 1 > maxBytes) {
                val oldest = records.removeFirstOrNull() ?: break
                byteSize -= oldest.line.length + 1
                dropped = true
                MonitaLog.debug { "Queue cap reached, dropped oldest event id ${oldest.id}" }
            }
            records.addLast(Record(id, shared, event, line))
            byteSize += line.length + 1
            if (dropped) {
                rewrite()
            } else {
                file.appendText(line + "\n")
            }
            true
        } catch (t: Throwable) {
            MonitaLog.error("Failed to persist event", t)
            false
        }
    }

    /** Removes acknowledged records (after a 2xx from collect) and compacts the file. */
    @Synchronized
    fun acknowledge(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val idSet = ids.toHashSet()
        val before = records.size
        val kept = records.filter { it.id !in idSet }
        if (kept.size == before) return
        records.clear()
        records.addAll(kept)
        byteSize = records.sumOf { it.line.length + 1L }
        rewrite()
    }

    @Synchronized
    fun clear() {
        records.clear()
        byteSize = 0
        try {
            file.delete()
        } catch (t: Throwable) {
        }
    }

    private fun rewrite() {
        try {
            val tmp = File(file.parentFile, "queue.jsonl.tmp")
            tmp.writeText(records.joinToString(separator = "") { it.line + "\n" })
            if (!tmp.renameTo(file)) {
                file.writeText(records.joinToString(separator = "") { it.line + "\n" })
                tmp.delete()
            }
        } catch (t: Throwable) {
            MonitaLog.error("Failed to compact event queue", t)
        }
    }
}
