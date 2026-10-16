package org.gotson.komga.domain.service

/**
 * Exact series-name key used only when file hashes are not available yet.
 * Symbols, spaces, completion markers, and Korean volume markers are removed.
 * Comparison is equality of the result, never substring containment.
 */
object SeriesNameNormalizer {
  private val completionMarker =
    Regex("""[(（\[【]\s*[완完]\s*[)）\]】]""")
  private val completedWord = Regex("""완결""")
  private val volumeRange = Regex("""제\s*\d+\s*[~～\-–—]\s*\d+\s*권""")
  private val volumeSingle = Regex("""제\s*\d+\s*권""")
  private val notLetterOrDigit = Regex("""[^\p{L}\p{N}]""")

  fun normalize(name: String): String =
    notLetterOrDigit
      .replace(
        volumeSingle.replace(
          volumeRange.replace(
            completedWord.replace(
              completionMarker.replace(name, ""),
              "",
            ),
            "",
          ),
          "",
        ),
        "",
      ).lowercase()
}
