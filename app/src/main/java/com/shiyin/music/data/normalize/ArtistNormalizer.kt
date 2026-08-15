package com.shiyin.music.data.normalize

/**
 * v2.0: Normalizes artist names for duplicate detection.
 *
 * Pipeline: lowercase → full-width → half-width → strip (Live)/(Cover)/(Remastered) etc. → trim.
 */
object ArtistNormalizer {

    // Suffixes that indicate a variant performance / reissue — strip them.
    private val STRIP_PATTERNS = listOf(
        Regex("""\s*\([^)]*(?:live|cover|remaster|remix|edit|version|mix|acoustic|deluxe|bonus|explicit|instrumental|reprise|reissue|demo|single|mono|stereo|feat|featuring|with|vs)[^)]*\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*[-–—]\s*[^)]*(?:live|cover|remaster|remix|edit|acoustic|instrumental|reprise|demo)[^)]*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*[\[\(][^\]\)]*(?:feat|featuring|with|prod|produced|ft)[^\]\)]*[\]\)]""", RegexOption.IGNORE_CASE),
    )

    // Artist names that are known to be the same despite different written forms
    private val KNOWN_EQUIVALENCES = mapOf(
        // Chinese — simplified/traditional
        "周杰倫" to "周杰伦",
        "張學友" to "张学友",
        "劉德華" to "刘德华",
        "陳奕迅" to "陈奕迅",
        "王力宏" to "王力宏",
        "林俊傑" to "林俊杰",
        "蔡依林" to "蔡依林",
        "孫燕姿" to "孙燕姿",
        "鄧紫棋" to "邓紫棋",
        "田馥甄" to "田馥甄",
        "S.H.E" to "SHE",
        "F.I.R" to "FIR",
        // Japanese
        "ＹＵＩ" to "YUI",
        "ＺＡＲＤ" to "ZARD",
        // Common normalization
        "various artists" to "Various Artists",
        "unknown artist" to "未知歌手",
    )

    /**
     * Normalize a name for comparison: returns a canonical form.
     * Does NOT strip punctuation — only removes variant suffixes and normalizes
     * full-width characters.
     */
    fun normalize(input: String): String {
        var s = input.lowercase().trim()
        // Full-width → half-width (numbers, letters, symbols)
        s = fullwidthToHalfwidth(s)
        // Strip variant suffixes
        for (p in STRIP_PATTERNS) {
            s = s.replace(p, "")
        }
        // Collapse multiple spaces
        s = s.replace(Regex("""\s+"""), " ")
        return s.trim()
    }

    /**
     * Check whether two artist names refer to the same real artist.
     * Uses [normalize] + known equivalences.
     */
    fun isSameArtist(a: String, b: String): Boolean {
        if (a.equals(b, ignoreCase = true)) return true
        val na = normalize(a)
        val nb = normalize(b)
        if (na == nb) return true
        // Check known equivalences
        val resolvedA = KNOWN_EQUIVALENCES[a] ?: a
        val resolvedB = KNOWN_EQUIVALENCES[b] ?: b
        return resolvedA.equals(resolvedB, ignoreCase = true)
    }

    /** Find all names in [names] that are likely duplicates of [target]. */
    fun findDuplicates(target: String, names: Set<String>): List<String> {
        val nt = normalize(target)
        return names.filter { it != target && normalize(it) == nt }
    }

    /** Group a set of names into clusters of likely-same artists. */
    fun cluster(names: Collection<String>): List<Set<String>> {
        val remaining = names.toMutableSet()
        val clusters = mutableListOf<Set<String>>()
        while (remaining.isNotEmpty()) {
            val pivot = remaining.first()
            val cluster = mutableSetOf(pivot)
            remaining.remove(pivot)
            val dupes = remaining.filter { isSameArtist(pivot, it) }
            cluster.addAll(dupes)
            remaining.removeAll(dupes)
            clusters += cluster
        }
        return clusters
    }

    private fun fullwidthToHalfwidth(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when {
                c.code in 0xFF01..0xFF5E -> sb.append((c.code - 0xFEE0).toChar()) // full-width letters/numbers → half-width
                c == '　' -> sb.append(' ') // full-width space
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}