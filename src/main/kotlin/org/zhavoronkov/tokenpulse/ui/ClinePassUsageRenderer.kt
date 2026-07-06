package org.zhavoronkov.tokenpulse.ui

import org.zhavoronkov.tokenpulse.provider.cline.ClineProviderClient

/**
 * Renders optional ClinePass usage information for the Cline tooltip.
 *
 * The renderer consumes only the provider-specific metadata keys populated by
 * [ClineProviderClient] and produces HTML rows compatible with the existing
 * status bar tooltip table.
 *
 * When ClinePass metadata is absent, [buildRows] produces an empty string so
 * the caller renders no ClinePass-specific content. When ClinePass metadata
 * is available, the output is a self-contained "ClinePass" section with a
 * labeled header and usage rows (5-hour / Weekly / Monthly).
 */
object ClinePassUsageRenderer {

    /** Title used for the ClinePass subsection header row. */
    const val SECTION_TITLE: String = "ClinePass"

    /** Color used for the ClinePass section header text. */
    private const val SECTION_HEADER_COLOR: String = "#BBBBBB"

    /**
     * Returns `true` if at least one recognized ClinePass usage field is present
     * in [metadata]. Used to decide whether the tooltip should show ClinePass rows.
     */
    fun hasUsage(metadata: Map<String, String>?): Boolean {
        if (metadata == null) return false
        return metadata.any { (key, value) ->
            key in USED_KEYS && !value.isNullOrBlank()
        }
    }

    /**
     * Builds the ClinePass usage section of the tooltip as raw HTML rows.
     *
     * Returns an empty string when no recognized ClinePass usage is available.
     * Recognized usage entries are rendered in the order: 5-hour, Weekly, Monthly,
     * each via [ProgressBarRenderer.buildUsageSection].
     */
    fun buildRows(metadata: Map<String, String>?): String {
        if (!hasUsage(metadata) || metadata == null) return ""

        return buildString {
            append(sectionHeaderRow())
            val fiveHour = metadata[ClineProviderClient.METADATA_FIVE_HOUR_USED]?.toIntOrNull()
            if (fiveHour != null) {
                append(
                    ProgressBarRenderer.buildUsageSection(
                        "5-hour",
                        fiveHour,
                        metadata[ClineProviderClient.METADATA_FIVE_HOUR_RESETS_AT]
                    )
                )
            }
            val weekly = metadata[ClineProviderClient.METADATA_WEEKLY_USED]?.toIntOrNull()
            if (weekly != null) {
                append(
                    ProgressBarRenderer.buildUsageSection(
                        "Weekly",
                        weekly,
                        metadata[ClineProviderClient.METADATA_WEEKLY_RESETS_AT]
                    )
                )
            }
            val monthly = metadata[ClineProviderClient.METADATA_MONTHLY_USED]?.toIntOrNull()
            if (monthly != null) {
                append(
                    ProgressBarRenderer.buildUsageSection(
                        "Monthly",
                        monthly,
                        metadata[ClineProviderClient.METADATA_MONTHLY_RESETS_AT]
                    )
                )
            }
        }
    }

    /**
     * Render the ClinePass subsection header row. Inlined (rather than reusing
     * a shared progress-bar primitive) so this renderer stays self-contained
     * and does not depend on optional progress-bar helper additions.
     */
    private fun sectionHeaderRow(): String {
        return "<tr><td colspan='2' style='padding-top: 4px;'>" +
            "<font color='$SECTION_HEADER_COLOR'><b>$SECTION_TITLE</b></font></td></tr>"
    }

    private val USED_KEYS = setOf(
        ClineProviderClient.METADATA_FIVE_HOUR_USED,
        ClineProviderClient.METADATA_WEEKLY_USED,
        ClineProviderClient.METADATA_MONTHLY_USED
    )
}
