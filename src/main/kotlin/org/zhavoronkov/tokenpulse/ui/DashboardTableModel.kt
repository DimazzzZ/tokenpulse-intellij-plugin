package org.zhavoronkov.tokenpulse.ui

import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.service.BalanceRefreshService
import org.zhavoronkov.tokenpulse.settings.Account
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Table model for the Dashboard dialog.
 *
 * Columns (in order):
 *  0  Provider      – provider display name
 *  1  API Key       – masked key preview (e.g. "sk-or-…91bc")
 *  2  Status        – last fetch result
 *  3  Last Updated  – time of last fetch
 *  4  Credits       – remaining credits from last successful fetch
 */
class DashboardTableModel : ListTableModel<Account>(
    PROVIDER_COLUMN,
    KEY_PREVIEW_COLUMN,
    STATUS_COLUMN,
    LAST_UPDATED_COLUMN,
    CREDITS_COLUMN
) {
    companion object {
        private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault())

        private val PROVIDER_COLUMN = object : ColumnInfo<Account, String>("Provider") {
            override fun valueOf(item: Account): String = item.providerId.displayName
        }

        private val KEY_PREVIEW_COLUMN = object : ColumnInfo<Account, String>("API Key") {
            override fun valueOf(item: Account): String = item.keyPreview.ifEmpty { "—" }
        }

        private val STATUS_COLUMN = object : ColumnInfo<Account, String>("Status") {
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

        private val LAST_UPDATED_COLUMN = object : ColumnInfo<Account, String>("Last Updated") {
            override fun valueOf(item: Account): String {
                val result = BalanceRefreshService.getInstance().results.value[item.id] ?: return "--"
                return timeFormatter.format(result.timestamp)
            }
        }

        private val CREDITS_COLUMN = object : ColumnInfo<Account, String>("Credits") {
            override fun valueOf(item: Account): String {
                val result = BalanceRefreshService.getInstance().results.value[item.id]
                return if (result is ProviderResult.Success) {
                    BalanceFormatter.format(result.snapshot.balance)
                } else {
                    "--"
                }
            }
        }
    }
}
