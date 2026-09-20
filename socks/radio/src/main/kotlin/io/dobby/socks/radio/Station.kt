package io.dobby.socks.radio

/**
 * One station in the constant table.
 *
 * [aliases] are written the way a person says them, **not** pre-normalized. Both they and the
 * spoken `{station}` slot go through [StationKey.of], so the table stays readable and the two
 * sides cannot drift — which stopped being a style preference the moment it turned out that
 * `Normalizer` digitises German number words before a Sock ever sees them
 * (`radio-plan.md` §C1).
 */
data class Station(
    val id: String,
    val displayName: String,
    val aliases: List<String>,
    val streamUrl: String,
    /**
     * Tried once after [streamUrl] has exhausted its retries (`radio-plan.md` §J1).
     *
     * Not a quality setting anybody picks: it is the reconnect ladder's last rung, because the
     * commonest reason a specific ORS endpoint stops answering is that endpoint and not the
     * network. Null where there is no second URL worth trying.
     */
    val fallbackUrl: String? = null,
    val isDefault: Boolean = false,
    /** Where the URLs came from and when they were last checked. Read by nothing at runtime. */
    val source: Source,
) {
    /**
     * Provenance, so `scripts/verify-streams` and a reader a year from now have a handle.
     *
     * ORF rotates its endpoints — the spec hedged every URL for that reason — so a table
     * verified once is a table that will be wrong eventually and silently. This is what the
     * script looks the station up by.
     */
    data class Source(
        val browserUuid: String,
        val homepage: String,
        val codec: String,
        val bitrateKbps: Int,
        val verifiedOn: String,
    )
}
