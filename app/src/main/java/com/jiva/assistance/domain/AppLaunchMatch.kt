package com.jiva.assistance.domain

/**
 * Resolve only a single unambiguous installed launcher label.
 *
 * Do not treat an empty label as a match, and do not silently launch the first
 * result if several installed apps share or partially match a spoken name.
 */
object AppLaunchMatch {
    fun select(query: String, labels: List<String>): Int? {
        val requested = query.trim().replace(Regex("\\s+"), " ").lowercase()
        if (requested.isEmpty()) return null
        val normalized = labels.map {
            it.trim().replace(Regex("\\s+"), " ").lowercase()
        }
        fun unique(indices: List<Int>): Int? =
            if (indices.size == 1) indices.single() else null
        val exact = normalized.indices.filter { normalized[it] == requested }
        if (exact.isNotEmpty()) return unique(exact)
        // Short app labels are intentionally exact-only to avoid launching
        // "PhonePe" when the owner simply says "Phone".
        if (requested.length < 4) return null
        val prefix = normalized.indices.filter {
            normalized[it].isNotEmpty() && normalized[it].startsWith(requested)
        }
        return unique(prefix)
    }
}
