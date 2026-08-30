package dev.securemesh.commander.domain.model

import dev.securemesh.commander.domain.service.LinkHealthGrade
import dev.securemesh.commander.domain.service.assessLinkHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkHealthV104Test {
    @Test fun healthyLinkUsesMultipleMetrics() {
        val now = 100_000L
        val link = MeshLink("A", "B", rssi = -67, snr = 8.0, pdr = .98, retries = 0, lastSeenEpochMs = now - 500)
        val result = assessLinkHealth(link, now)
        assertEquals(LinkHealthGrade.EXCELLENT, result.grade)
        assertTrue((result.score ?: 0) >= 82)
    }

    @Test fun staleLinkIsLostWithoutInventingRadioEvidence() {
        val now = 100_000L
        val link = MeshLink("A", "B", rssi = -60, snr = 10.0, pdr = 1.0, retries = 0, lastSeenEpochMs = now - 31_000)
        assertEquals(LinkHealthGrade.LOST, assessLinkHealth(link, now).grade)
    }

    @Test fun missingMetricsRemainUnknown() {
        assertEquals(LinkHealthGrade.UNKNOWN, assessLinkHealth(MeshLink("A", "B"), 100_000L).grade)
    }
}
