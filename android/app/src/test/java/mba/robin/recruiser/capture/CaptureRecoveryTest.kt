package mba.robin.recruiser.capture

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureRecoveryTest {
    @get:Rule val temporary = TemporaryFolder(File("build/test-work").apply { mkdirs() })

    private fun journal(bytes: ByteArray): File = File(temporary.newFolder(), "observations/frames.jsonl").apply {
        parentFile!!.mkdirs(); writeBytes(bytes)
    }
    private fun backup(file: File) = File(file.parentFile!!.parentFile, "recovery").listFiles()!!.single()

    @Test fun completeUnterminatedRecordSurvivesAndNextAppendRemainsSeparate() {
        val original = "{\"frame\":1}\n{\"frame\":2}".toByteArray()
        val file = journal(original)
        assertNotNull(CapturePackageWriter.recoverJournal(file))
        assertArrayEquals(original, backup(file).readBytes())
        file.appendText("{\"frame\":3}\n")
        assertEquals(listOf(1, 2, 3), file.readLines().map { JSONObject(it).getInt("frame") })
        assertNull(CapturePackageWriter.recoverJournal(file))
    }

    @Test fun malformedTailIsBackedUpAndOnlyCompletePrefixRemains() {
        val original = "{\"frame\":1}\n{\"frame\":".toByteArray()
        val file = journal(original)
        assertNotNull(CapturePackageWriter.recoverJournal(file))
        assertArrayEquals(original, backup(file).readBytes())
        assertEquals("{\"frame\":1}\n", file.readText())
        assertNull(CapturePackageWriter.recoverJournal(file))
    }

    @Test fun concatenatedObjectsAreAnInvalidRecordEvenIfFirstObjectParses() {
        val original = "{\"frame\":1}\n{\"frame\":2}{\"frame\":3}\n".toByteArray()
        val file = journal(original)
        assertNotNull(CapturePackageWriter.recoverJournal(file))
        assertEquals("{\"frame\":1}\n", file.readText())
        assertArrayEquals(original, backup(file).readBytes())
    }

    @Test fun utf8RecordsArePreservedAndInvalidUtf8TailDoesNotBecomeCommitted() {
        val text = "{\"detail\":\"entrée 東京\"}\n"
        val good = journal(text.toByteArray())
        assertNull(CapturePackageWriter.recoverJournal(good))
        assertEquals(text, good.readText())
        val bad = journal(text.toByteArray() + byteArrayOf(123, 34, 120, 34, 58, 34, -1, 34, 125, 10))
        assertNotNull(CapturePackageWriter.recoverJournal(bad))
        assertEquals(text, bad.readText())
    }

    @Test fun newlineTerminatedAndEmptyJournalsDoNotCreateRecoveryCopies() {
        for (content in listOf("", "{\"frame\":1}\n", "{\"frame\":1}\r\n")) {
            val file = journal(content.toByteArray())
            assertNull(CapturePackageWriter.recoverJournal(file))
            assertEquals(content, file.readText())
            assertFalse(File(file.parentFile!!.parentFile, "recovery").exists())
        }
    }

    @Test fun everyConsumedInputCountsTowardCheckpointEvenWithSparseDepth() {
        val cadence = CheckpointCadence(0)
        val saved = mutableListOf<Int>()
        // Usable depth occurs only at next-frame indices 1 mod 6; many multiples of 25 are skipped.
        var integrated = 0
        for (nextFrame in 1..103) {
            if (nextFrame % 6 == 1) integrated++
            cadence.completed(nextFrame) { saved.add(nextFrame) }
        }
        assertEquals(18, integrated)
        assertEquals(listOf(25, 50, 75, 100), saved)
    }

    @Test fun resumedCheckpointCadenceStartsAtDurableCursorAndFailedSaveStaysDue() {
        val cadence = CheckpointCadence(42)
        val saved = mutableListOf<Int>()
        for (nextFrame in 43..66) cadence.completed(nextFrame) { saved.add(nextFrame) }
        assertTrue(saved.isEmpty())
        assertThrows(IllegalStateException::class.java) { cadence.completed(67) { error("storage unavailable") } }
        cadence.completed(67) { saved.add(67) }
        assertEquals(listOf(67), saved)
        assertThrows(IllegalArgumentException::class.java) { cadence.completed(66) {} }
    }
}
