package org.zhavoronkov.tokenpulse.ui

import com.intellij.util.ui.ColumnInfo
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.service.BalanceRefreshService
import org.zhavoronkov.tokenpulse.settings.Account
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Shared column definitions for Account-based table models.
 *
 * Extracted to eliminate duplication between [AccountTableModel] and [DashboardTableModel].
 * Both models display account information but with slightly different column sets.
 */
object AccountTableColumns {

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    /** Provider display name column. */
    val PROVIDER = object : ColumnInfo<Account, String>("Provider") {
        override fun valueOf(item: Account): String = item.connectionType.fullDisplayName
    }

    /** Masked API key preview column. */
    val KEY_PREVIEW = object : ColumnInfo<Account, String>("API Key") {
        override fun valueOf(item: Account): String = item.keyPreview.ifEmpty { "—" }
    }

    /** Last fetch result status column. */
    val STATUS = object : ColumnInfo<Account, String>("Status") {
        override fun valueOf(item: Account): String {
            val result = BalanceRefreshService.getInstance().results.value[item.id]
            return when (result) {
                null -> "Never"
                is ProviderResult.Success -> "OK"
                is ProviderResult.Failure.AuthError -> "Auth Error"
                is ProviderResult.Failure.RateLimited -> "Rate Limited"
                is ProviderResult.Failure -> "Error"
            }
        }
    }

    /** Time of last fetch column. */
    val LAST_UPDATED = object : ColumnInfo<Account, String>("Last Updated") {
        override fun valueOf(item: Account): String {
            val result = BalanceRefreshService.getInstance().results.value[item.id] ?: return "--"
            return timeFormatter.format(result.timestamp)
        }
    }

    /** Remaining credits from last successful fetch column. */
    val CREDITS = object : ColumnInfo<Account, String>("Credits") {
        override fun valueOf(item: Account): String {
            val result = BalanceRefreshService.getInstance().results.value[item.id]
            if (result !is ProviderResult.Success) return "--"

            // For usage-percentage types, show usage percentages instead of fake credits
            if (BalanceFormatter.isUsagePercentageType(result.snapshot.connectionType)) {
                return formatUsagePercentage(result.snapshot.metadata)
            }

            return BalanceFormatter.format(result.snapshot.balance)
        }

        private fun formatUsagePercentage(metadata: Map<String, String>): String {
            // Try OAuth data first, then CLI data
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
