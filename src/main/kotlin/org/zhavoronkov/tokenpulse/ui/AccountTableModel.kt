package org.zhavoronkov.tokenpulse.ui

import com.intellij.util.ui.ListTableModel
import org.zhavoronkov.tokenpulse.settings.Account

/**
 * Table model for the Settings → Accounts table.
 *
 * Columns (in order):
 *  0  Provider   – provider display name
 *  1  API Key    – masked key preview (e.g. "sk-or-…91bc")
 *  2  Status     – last fetch result
 *  3  Last Updated – time of last fetch
 *  4  Credits    – remaining credits from last successful fetch
 *  5  Enabled    – checkbox
 *
 * Uses shared column definitions from [AccountTableColumns].
 */
@Suppress("SpreadOperator")
class AccountTableModel : ListTableModel<Account>(*AccountTableColumns.allColumns())
