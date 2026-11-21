package com.openmate.app.connection.v2

import com.openmate.app.connection.LocalIpv4

data class LanCandidate(
    val ip: String,
    val port: Int,
    val scope: String,
)

object LanCandidateRanking {
    const val MAX_CANDIDATES = 4

    const val SCOPE_PRIVATE = "private"
    const val SCOPE_CGNAT = "cgnat"
    const val SCOPE_OTHER = "other"

    fun rank(
        candidates: List<LanCandidate>,
        localIpv4: List<LocalIpv4>,
        allowPrivate: Boolean,
    ): List<LanCandidate> {
        return candidates
            .filter { it.ip.isNotBlank() && it.port > 0 }
            .distinctBy { "${it.ip}:${it.port}" }
            .filter { candidate ->
                candidate.scope == SCOPE_CGNAT || allowPrivate
            }
            .sortedBy { score(it, localIpv4) }
            .take(MAX_CANDIDATES)
    }

    fun scopeOf(ip: String): String {
        val value = ipv4ToInt(ip) ?: return SCOPE_OTHER
        val a = (value ushr 24) and 0xFF
        val b = (value ushr 16) and 0xFF
        val isPrivate = a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
        if (isPrivate) return SCOPE_PRIVATE
        if (a == 100 && b in 64..127) return SCOPE_CGNAT
        return SCOPE_OTHER
    }

    private fun score(candidate: LanCandidate, localIpv4: List<LocalIpv4>): Int {
        val sameSubnet = localIpv4.any { local ->
            isSameSubnet(candidate.ip, local.ip, local.prefixLength)
        }
        if (sameSubnet) return 0
        return when (candidate.scope) {
            SCOPE_PRIVATE -> 1
            SCOPE_CGNAT -> 2
            else -> 3
        }
    }

    private fun isSameSubnet(target: String, local: String, prefixLength: Int): Boolean {
        val targetValue = ipv4ToInt(target) ?: return false
        val localValue = ipv4ToInt(local) ?: return false
        val bits = prefixLength.coerceIn(0, 32)
        val mask = if (bits == 0) 0 else (-1 shl (32 - bits))
        return (targetValue and mask) == (localValue and mask)
    }

    private fun ipv4ToInt(ip: String): Int? {
        val parts = ip.split(".")
        if (parts.size != 4) return null
        var value = 0
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            value = (value shl 8) or octet
        }
        return value
    }
}

object LanBackoff {
    private val INTERVALS_MS = longArrayOf(60_000L, 120_000L, 240_000L, 480_000L, 900_000L)

    fun intervalMs(failures: Int): Long {
        if (failures <= 0) return INTERVALS_MS[0]
        return INTERVALS_MS[failures.coerceAtMost(INTERVALS_MS.lastIndex)]
    }
}
