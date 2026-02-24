package org.zhavoronkov.tokenpulse.ui

import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.service.BalanceRefreshService
import org.zhavoronkov.tokenpulse.settings.Account
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class AccountTableModel : ListTableModel<Account>(
    NAME_COLUMN,
    PROVIDER_COLUMN,
    STATUS_COLUMN,
    LAST_UPDATED_COLUMN,
    ENABLED_COLUMN
) {
    companion object {
        private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault())

        private val NAME_COLUMN = object : ColumnInfo<Account, String>("Name") {
            override fun valueOf(item: Account): String = item.name
        }

        private val PROVIDER_COLUMN = object : ColumnInfo<Account, String>("Provider") {
            override fun valueOf(item: Account): String = item.providerId.displayName
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

        private val ENABLED_COLUMN = object : ColumnInfo<Account, Boolean>("Enabled") {
            override fun valueOf(item: Account): Boolean = item.isEnabled
            override fun getColumnClass(): Class<*> = Boolean::class.java
        }
    }
}
