package com.openmate.app.connection.v2

import com.google.common.truth.Truth.assertThat
import com.openmate.app.connection.LocalIpv4
import org.junit.Test

class LanCandidateRankingTest {

    private val phoneWifi = listOf(LocalIpv4("192.168.5.11", 24))

    private fun candidate(ip: String, scope: String = LanCandidateRanking.scopeOf(ip)) =
        LanCandidate(ip = ip, port = 10082, scope = scope)

    @Test
    fun rank_prefersSameSubnetFirst() {
        val ranked = LanCandidateRanking.rank(
            candidates = listOf(
                candidate("100.88.12.3"),
                candidate("10.0.0.7"),
                candidate("192.168.5.13"),
            ),
            localIpv4 = phoneWifi,
            allowPrivate = true,
        )

        assertThat(ranked.map { it.ip }).containsExactly("192.168.5.13", "10.0.0.7", "100.88.12.3").inOrder()
    }

    @Test
    fun rank_onCellularKeepsOnlyCgnatCandidates() {
        val ranked = LanCandidateRanking.rank(
            candidates = listOf(
                candidate("192.168.5.13"),
                candidate("100.88.12.3"),
                candidate("8.8.8.8"),
            ),
            localIpv4 = emptyList(),
            allowPrivate = false,
        )

        assertThat(ranked.map { it.ip }).containsExactly("100.88.12.3")
    }

    @Test
    fun rank_dedupesAndCapsCandidates() {
        val ranked = LanCandidateRanking.rank(
            candidates = listOf(
                candidate("192.168.5.13"),
                candidate("192.168.5.13"),
                candidate("192.168.5.20"),
                candidate("192.168.5.21"),
                candidate("192.168.5.22"),
                candidate("192.168.5.23"),
            ),
            localIpv4 = phoneWifi,
            allowPrivate = true,
        )

        assertThat(ranked).hasSize(LanCandidateRanking.MAX_CANDIDATES)
        assertThat(ranked.map { it.ip }.toSet()).hasSize(LanCandidateRanking.MAX_CANDIDATES)
    }

    @Test
    fun rank_dropsInvalidCandidates() {
        val ranked = LanCandidateRanking.rank(
            candidates = listOf(
                LanCandidate(ip = "", port = 10082, scope = "private"),
                LanCandidate(ip = "192.168.5.13", port = 0, scope = "private"),
            ),
            localIpv4 = phoneWifi,
            allowPrivate = true,
        )

        assertThat(ranked).isEmpty()
    }

    @Test
    fun scopeOf_classifiesRanges() {
        assertThat(LanCandidateRanking.scopeOf("192.168.5.13")).isEqualTo("private")
        assertThat(LanCandidateRanking.scopeOf("10.1.2.3")).isEqualTo("private")
        assertThat(LanCandidateRanking.scopeOf("172.20.0.1")).isEqualTo("private")
        assertThat(LanCandidateRanking.scopeOf("100.101.102.103")).isEqualTo("cgnat")
        assertThat(LanCandidateRanking.scopeOf("8.8.8.8")).isEqualTo("other")
        assertThat(LanCandidateRanking.scopeOf("nope")).isEqualTo("other")
    }

    @Test
    fun backoff_growsAndCaps() {
        assertThat(LanBackoff.intervalMs(0)).isEqualTo(60_000L)
        assertThat(LanBackoff.intervalMs(1)).isEqualTo(120_000L)
        assertThat(LanBackoff.intervalMs(2)).isEqualTo(240_000L)
        assertThat(LanBackoff.intervalMs(3)).isEqualTo(480_000L)
        assertThat(LanBackoff.intervalMs(4)).isEqualTo(900_000L)
        assertThat(LanBackoff.intervalMs(10)).isEqualTo(900_000L)
    }
}
