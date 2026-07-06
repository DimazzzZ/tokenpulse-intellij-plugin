package org.zhavoronkov.tokenpulse.ui

import org.zhavoronkov.tokenpulse.provider.cline.ClineProviderClient

/**
 * Represents a single ClinePass usage metric extracted from provider metadata.
 *
 * @property label Human-readable label (e.g. "5-hour", "Weekly", "Monthly").
 * @property percent Usage percentage (0–100).
 * @property resetAt Optional reset timestamp string.
 */
data class ClinePassMetric(
    val label: String,
    val percent: Int,
    val resetAt: String?
)

/**
 * Extracts optional ClinePass usage information from provider metadata.
 *
 * The extractor consumes only the provider-specific metadata keys populated by
 * [ClineProviderClient] and produces structured [ClinePassMetric] objects.
 * The tooltip panel ([TokenPulseStatusTooltipPanel]) is responsible for
 * rendering these metrics as HTML rows.
 *
 * When ClinePass metadata is absent, [extractMetrics] returns an empty list so
 * the caller renders no ClinePass-specific content.
 */
object ClinePassUsageRenderer {

    /** Title used for the ClinePass subsection header row. */
    const val SECTION_TITLE: String = "ClinePass"

    /**
     * Returns `true` if at least one recognized ClinePass usage field is present
     * in [metadata]. Used to decide whether the tooltip should show ClinePass rows.
     */
    fun hasUsage(metadata: Map<String, String>?): Boolean {
        return extractMetrics(metadata).isNotEmpty()
    }

    /**
     * Extracts ClinePass usage metrics from [metadata] in a deterministic order:
     * 5-hour → Weekly → Monthly.
     *
     * Returns an empty list when no recognized ClinePass usage is available.
     */
    fun extractMetrics(metadata: Map<String, String>?): List<ClinePassMetric> {
        if (metadata == null) return emptyList()

        val metrics = mutableListOf<ClinePassMetric>()
        val fiveHour = metadata[ClineProviderClient.METADATA_FIVE_HOUR_USED]?.toIntOrNull()
        if (fiveHour != null) {
            metrics.add(ClinePassMetric("5-hour", fiveHour, metadata[ClineProviderClient.METADATA_FIVE_HOUR_RESETS_AT]))
        }
        val weekly = metadata[ClineProviderClient.METADATA_WEEKLY_USED]?.toIntOrNull()
        if (weekly != null) {
            metrics.add(ClinePassMetric("Weekly", weekly, metadata[ClineProviderClient.METADATA_WEEKLY_RESETS_AT]))
        }
        val monthly = metadata[ClineProviderClient.METADATA_MONTHLY_USED]?.toIntOrNull()
        if (monthly != null) {
            metrics.add(ClinePassMetric("Monthly", monthly, metadata[ClineProviderClient.METADATA_MONTHLY_RESETS_AT]))
        }
        return metrics
    }

    /**
     * Returns the set of recognized ClinePass usage metadata keys.
     */
    val USED_KEYS = setOf(
        ClineProviderClient.METADATA_FIVE_HOUR_USED,
        ClineProviderClient.METADATA_WEEKLY_USED,
        ClineProviderClient.METADATA_MONTHLY_USED
    )
}
