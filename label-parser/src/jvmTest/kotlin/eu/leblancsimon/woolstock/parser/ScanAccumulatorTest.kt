package eu.leblancsimon.woolstock.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScanAccumulatorTest {
    private fun result(lot: String?) =
        LabelParseResult(lot?.let { Candidate(it, 0.8f, 0) }, null, null, null)

    @Test
    fun `le vote retient la valeur la plus fréquente`() {
        val voter = CandidateVoter(windowSize = 15, minVotes = 3, minShare = 0.5f)
        listOf("1185", "7185", "1185", null, "1185", "7185").forEach { voter.add(it?.let { v -> Candidate(v, 0.8f, 0) }) }
        val vote = voter.tally()!!
        assertEquals("1185", vote.value)
        assertEquals(3, vote.votes)
        assertEquals(5, vote.considered)
        assertTrue(vote.accepted)
    }

    @Test
    fun `sous le seuil, la valeur est proposée mais pas acceptée`() {
        val voter = CandidateVoter(minVotes = 3)
        voter.add(Candidate("4512", 0.9f, 0))
        val vote = voter.tally()!!
        assertEquals("4512", vote.value)
        assertFalse(vote.accepted)
    }

    @Test
    fun `la fenêtre oublie les vieilles frames`() {
        val voter = CandidateVoter(windowSize = 3, minVotes = 1)
        voter.add(Candidate("A", 1f, 0))
        repeat(3) { voter.add(null) }
        assertNull(voter.tally())
    }

    @Test
    fun `capture automatique après une seconde de stabilité`() {
        val acc = ScanAccumulator(autoCaptureAfterMs = 1_000)
        var t = 0L
        var snapshot = ScanSnapshot()
        repeat(4) {
            snapshot = acc.add("3614090123456", result("4512"), t)
            t += 200
        }
        assertFalse(acc.isReadyForAutoCapture(snapshot))
        repeat(6) {
            snapshot = acc.add("3614090123456", result("4512"), t)
            t += 200
        }
        assertTrue(acc.isReadyForAutoCapture(snapshot))
        assertEquals("4512", snapshot.dyeLot?.value)
    }

    @Test
    fun `un changement de bain relance le chrono`() {
        val acc = ScanAccumulator(windowSize = 4, minVotes = 2, autoCaptureAfterMs = 1_000)
        var t = 0L
        repeat(8) { acc.add("3614090123456", result("4512"), t); t += 200 }
        repeat(4) { acc.add("3614090123456", result("9999"), t); t += 200 }
        assertFalse(acc.isReadyForAutoCapture())
        acc.reset()
        assertEquals(ScanSnapshot(), acc.snapshot())
    }

    @Test
    fun `normalise les GTIN`() {
        assertEquals("0012345678905", Gtin.normalize("012345678905"))
        assertEquals("3614090123456", Gtin.normalize("3614090123456"))
        assertNull(Gtin.normalize("ABC"))
        assertNull(Gtin.normalize("12345"))
    }
}
