package io.dobby.core.testing

/**
 * Everyday German sentences that are not commands, for the filler-skipping tests (M6c).
 *
 * A test fixture rather than a test resource because two modules need it and they are not the
 * same module: `:core` runs it against the spec fixtures, and `:android:app` runs it against
 * the Socks that actually ship — which are disjoint catalogs, and a rule that only holds for
 * one of them is not a rule.
 *
 * The corpus and the argument for it are in `de-sentences-negative.txt` itself.
 */
object NegativeSentences {

    fun load(): List<String> {
        val stream = checkNotNull(javaClass.getResourceAsStream("/$RESOURCE")) {
            "$RESOURCE is missing from core's test fixtures"
        }
        return stream.bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()
        }
    }

    const val RESOURCE: String = "de-sentences-negative.txt"
}
