package org.zhavoronkov.tokenpulse.ui

import com.intellij.util.ui.ListTableModel
import org.zhavoronkov.tokenpulse.settings.Account

/**
 * Table model for the Dashboard dialog.
 *
 * Columns (in order):
 *  0  Provider      – provider display name
 *  1  API Key       – masked key preview (e.g. "sk-or-…91bc")
 *  2  Status        – last fetch result
 *  3  Last Updated  – time of last fetch
 *  4  Credits       – remaining credits from last successful fetch
 *
 * Uses shared column definitions from [AccountTableColumns].
 */
@Suppress("SpreadOperator")
class DashboardTableModel : ListTableModel<Account>(*AccountTableColumns.baseColumns())
