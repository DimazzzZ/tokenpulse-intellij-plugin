package org.zhavoronkov.tokenpulse.ui

import com.intellij.util.ui.ColumnInfo
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.service.BalanceRefreshService
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.settings.claudeConfigDirLabel
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── Pure column-value helpers (extracted for testability) ────────────────
// These functions carry the entire decision logic of each column. The
// `ColumnInfo.valueOf` overrides below are thin adapters that look up the
// live `ProviderResult` from `BalanceRefreshService` and delegate here.
// Keeping the logic top-level makes it unit-testable without spinning up an
// IntelliJ application service.

private val COLUMN_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

/** "API Key" column: config dir for Claude Code, masked preview otherwise. */
internal fun keyPreviewValue(account: Account): String =
    if (account.connectionType == ConnectionType.CLAUDE_CODE) {
        claudeConfigDirLabel(account.claudeConfigDir)
    } else {
        account.keyPreview.ifEmpty { "—" }
    }

/** "Status" column text from the last fetch result. */
internal fun statusValue(result: ProviderResult?): String = when (result) {
    null -> "Never"
    is ProviderResult.Success -> "OK"
    is ProviderResult.Failure.AuthError -> "Auth Error"
    is ProviderResult.Failure.RateLimited -> "Rate Limited"
    is ProviderResult.Failure -> "Error"
}

/** "Last Updated" column text: formatted timestamp or "--" when no result. */
internal fun lastUpdatedValue(result: ProviderResult?): String {
    val r = result ?: return "--"
    return COLUMN_TIME_FORMATTER.format(r.timestamp)
}

/**
 * "Credits" column text. Falls back to "--" for non-success results;
 * for usage-percentage providers, renders the two utilization windows.
 */
internal fun creditsValue(result: ProviderResult?): String {
    if (result !is ProviderResult.Success) return "--"
    if (BalanceFormatter.isUsagePercentageType(result.snapshot.connectionType)) {
        return formatUsagePercentage(result.snapshot.metadata)
    }
    return BalanceFormatter.format(result.snapshot.balance)
}

/**
 * Format the usage-percentage summary shown for Claude / Codex-like providers.
 *
 * Reads two orthogonal windows from `metadata`:
 *  - 5-hour: tries `fiveHourUtilization` (OAuth), then `sessionUsed`
 *    (CLI int %), then `fiveHourUsed` (CLI float %).
 *  - Weekly: tries `sevenDayUtilization` (OAuth), then `weekUsed`
 *    (CLI int %), then `weeklyUsed` (CLI float %).
 * Returns "--" if neither window is present. Percentages are expressed as the
 * REMAINING share (100 - used), matching prior in-cell wording.
 */
internal fun formatUsagePercentage(metadata: Map<String, String>): String {
    val fiveHour = metadata["fiveHourUtilization"]?.toIntOrNull()
        ?: metadata["sessionUsed"]?.toIntOrNull()
        ?: metadata["fiveHourUsed"]?.toFloatOrNull()?.toInt()
    val weekly = metadata["sevenDayUtilization"]?.toIntOrNull()
        ?: metadata["weekUsed"]?.toIntOrNull()
        ?: metadata["weeklyUsed"]?.toFloatOrNull()?.toInt()

    if (fiveHour == null && weekly == null) return "--"

    val parts = mutableListOf<String>()
    if (fiveHour != null) parts.add("${100 - fiveHour}% 5h")
    if (weekly != null) parts.add("${100 - weekly}% wk")
    return parts.joinToString(" • ")
}

/**
 * Shared column definitions for Account-based table models.
 *
 * Extracted to eliminate duplication between [AccountTableModel] and [DashboardTableModel].
 * Both models display account information but with slightly different column sets.
 */
object AccountTableColumns {

    /** Provider display name column. */
    val PROVIDER = object : ColumnInfo<Account, String>("Provider") {
        override fun valueOf(item: Account): String = item.connectionType.fullDisplayName
    }

    /** Masked API key preview column. Claude Code shows its config dir instead. */
    val KEY_PREVIEW = object : ColumnInfo<Account, String>("API Key") {
        override fun valueOf(item: Account): String = keyPreviewValue(item)
    }

    /** Last fetch result status column. */
    val STATUS = object : ColumnInfo<Account, String>("Status") {
        override fun valueOf(item: Account): String =
            statusValue(BalanceRefreshService.getInstance().results.value[item.id])
    }

    /** Time of last fetch column. */
    val LAST_UPDATED = object : ColumnInfo<Account, String>("Last Updated") {
        override fun valueOf(item: Account): String =
            lastUpdatedValue(BalanceRefreshService.getInstance().results.value[item.id])
    }

    /** Remaining credits from last successful fetch column. */
    val CREDITS = object : ColumnInfo<Account, String>("Credits") {
        override fun valueOf(item: Account): String =
            creditsValue(BalanceRefreshService.getInstance().results.value[item.id])
    }

    /** Editable enabled/disabled checkbox column. */
    val ENABLED = object : ColumnInfo<Account, Boolean>("Enabled") {
        override fun valueOf(item: Account): Boolean = item.isEnabled
        override fun getColumnClass(): Class<*> = Boolean::class.java
        override fun isCellEditable(item: Account): Boolean = true
        override fun setValue(item: Account, value: Boolean) {
            item.isEnabled = value
        }
    }

    /** Base columns used by both AccountTableModel and DashboardTableModel. */
    fun baseColumns(): Array<ColumnInfo<Account, *>> = arrayOf(
        PROVIDER,
        KEY_PREVIEW,
        STATUS,
        LAST_UPDATED,
        CREDITS
    )

    /** All columns including the editable Enabled column (for AccountTableModel). */
    fun allColumns(): Array<ColumnInfo<Account, *>> = arrayOf(
        PROVIDER,
        KEY_PREVIEW,
        STATUS,
        LAST_UPDATED,
        CREDITS,
        ENABLED
    )
}
