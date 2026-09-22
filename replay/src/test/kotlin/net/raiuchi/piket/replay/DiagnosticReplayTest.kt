package net.raiuchi.piket.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReplayTest {
    @Test
    fun cleanFixtureReplaysWithoutDifferences() {
        val text = checkNotNull(javaClass.classLoader.getResource("diagnostics-replay-clean.txt"))
            .readText(Charsets.UTF_8)
        val report = DiagnosticReplay.run(text)
        assertEquals(1, report.sessions.size)
        assertEquals(2, report.sessions.single().tickRecords)
        assertTrue(report.sessions.single().discrepancies.joinToString("\n"), report.clean)
    }

    @Test
    fun oldLogIsMarkedAsPartialInsteadOfPassingSilently() {
        val report = DiagnosticReplay.run("""{"elapsed_ms":1,"event":"trip_session_started","trip_session_id":"old","route":"СПбФин - Выборг","direction":"tuda","manual_official_m":0}""")
        assertEquals(1, report.sessions.size)
        assertTrue(!report.sessions.single().completeTrace)
        assertTrue(!report.clean)
    }
}