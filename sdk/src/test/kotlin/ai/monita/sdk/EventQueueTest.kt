// Copyright RNA Digital PTY LTD
package ai.monita.sdk

import ai.monita.sdk.internal.EventQueue
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EventQueueTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun shared(n: Int = 0): Map<String, Any?> = linkedMapOf("t" to "dom_x", "sv" to n.toString())

    private fun event(n: Int): Map<String, Any?> = linkedMapOf("e" to "ev_$n", "vn" to "V")

    @Test
    fun appendedEventsSurviveAReopen() {
        val dir = tmp.newFolder()
        val queue = EventQueue(dir)
        queue.append(shared(), event(1))
        queue.append(shared(), event(2))
        val reopened = EventQueue(dir)
        assertEquals(2, reopened.size)
        assertEquals("ev_1", reopened.snapshot()[0].event["e"])
        assertEquals("ev_2", reopened.snapshot()[1].event["e"])
    }

    @Test
    fun acknowledgeRemovesOnlyTheConfirmedRecords() {
        val dir = tmp.newFolder()
        val queue = EventQueue(dir)
        queue.append(shared(), event(1))
        queue.append(shared(), event(2))
        queue.append(shared(), event(3))
        val first = queue.snapshot().first()
        queue.acknowledge(listOf(first.id))
        assertEquals(2, queue.size)
        val reopened = EventQueue(dir)
        assertEquals(2, reopened.size)
        assertEquals("ev_2", reopened.snapshot()[0].event["e"])
    }

    @Test
    fun eventCapDropsOldestFirst() {
        val dir = tmp.newFolder()
        val queue = EventQueue(dir, maxEvents = 5)
        for (i in 1..8) {
            queue.append(shared(), event(i))
        }
        assertEquals(5, queue.size)
        assertEquals("ev_4", queue.snapshot().first().event["e"])
        assertEquals("ev_8", queue.snapshot().last().event["e"])
    }

    @Test
    fun byteCapDropsOldestFirst() {
        val dir = tmp.newFolder()
        val queue = EventQueue(dir, maxBytes = 2_000)
        for (i in 1..30) {
            queue.append(shared(), linkedMapOf("e" to "ev_$i", "blob" to "x".repeat(200)))
        }
        assertTrue(queue.size < 30)
        assertEquals("ev_30", queue.snapshot().last().event["e"])
    }

    @Test
    fun corruptLinesAreSkippedOnLoad() {
        val dir = tmp.newFolder()
        val queue = EventQueue(dir)
        queue.append(shared(), event(1))
        File(dir, "queue.jsonl").appendText("{not valid json\n")
        queue.append(shared(), event(2))
        val reopened = EventQueue(dir)
        assertEquals(2, reopened.size)
    }

    @Test
    fun aPartialTailFromAnInterruptedWriteIsTruncated() {
        val dir = tmp.newFolder()
        val queue = EventQueue(dir)
        queue.append(shared(), event(1))
        queue.append(shared(), event(2))
        // Simulate a crash mid-append: valid JSON prefix, no trailing newline.
        File(dir, "queue.jsonl").appendText("""{"id":99,"sh":{"t":"dom""")
        val reopened = EventQueue(dir)
        assertEquals(2, reopened.size)
        assertEquals("ev_2", reopened.snapshot().last().event["e"])
        // The load compacted the file, so appends stay well formed.
        reopened.append(shared(), event(3))
        assertEquals(3, EventQueue(dir).size)
    }

    @Test
    fun clearEmptiesQueueAndFile() {
        val dir = tmp.newFolder()
        val queue = EventQueue(dir)
        queue.append(shared(), event(1))
        queue.clear()
        assertEquals(0, queue.size)
        assertEquals(0, EventQueue(dir).size)
    }
}
