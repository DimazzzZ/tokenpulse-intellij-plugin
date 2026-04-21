@file:Suppress("MagicNumber")

package org.zhavoronkov.tokenpulse.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import org.zhavoronkov.tokenpulse.model.ChartType
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.model.TimeRange
import org.zhavoronkov.tokenpulse.service.BalanceHistoryService
import org.zhavoronkov.tokenpulse.service.BalanceRefreshService
import org.zhavoronkov.tokenpulse.settings.TokenPulseSettingsService
import org.zhavoronkov.tokenpulse.ui.chart.BalanceChartPanel
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ItemEvent
import javax.swing.Action
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane

/**
 * Dashboard dialog showing balance history chart and account overview.
 *
 * Features:
 * - Balance history chart (line or area) with unified % scale
 * - Time range selector (24h, 7d, 30d, all)
 * - Account table with current balances
 * - Refresh button
 */
class TokenPulseDashboardDialog(project: Project) : DialogWrapper(project) {

    private val chartPanel = BalanceChartPanel()
    private val tableModel = DashboardTableModel()

    private val chartTypeCombo = ComboBox(DefaultComboBoxModel(ChartType.entries.toTypedArray()))
    private val timeRangeCombo = ComboBox(DefaultComboBoxModel(TimeRange.entries.toTypedArray()))

    private var currentTimeRange = TimeRange.DAYS_7

    private val table = object : JBTable(tableModel) {
        override fun getToolTipText(e: java.awt.event.MouseEvent): String? {
            val row = rowAtPoint(e.point)
            val col = columnAtPoint(e.point)
            if (row < 0 || col < 0) return null

            val account = tableModel.getItem(convertRowIndexToModel(row))
            val result = BalanceRefreshService.getInstance().results.value[account.id]

            if (result is ProviderResult.Failure) {
                return result.message
            }
            return super.getToolTipText(e)
        }
    }

    companion object {
        private const val DIALOG_WIDTH = 700
        private const val DIALOG_HEIGHT = 550
        private const val CHART_HEIGHT = 260
        private const val TABLE_HEIGHT = 180

        // Combo box dimensions
        private const val CHART_TYPE_COMBO_WIDTH = 110
        private const val TIME_RANGE_COMBO_WIDTH = 120
        private const val COMBO_HEIGHT = 25

        // Split pane
        private const val SPLIT_PANE_RESIZE_WEIGHT = 0.6

        // Column indices (must match DashboardTableModel order)
        private const val COL_PROVIDER = 0
        private const val COL_KEY_PREVIEW = 1
        private const val COL_STATUS = 2
        private const val COL_LAST_UPDATED = 3
        private const val COL_CREDITS = 4

        // Column preferred widths
        private const val COL_PROVIDER_WIDTH = 120
        private const val COL_KEY_PREVIEW_WIDTH = 140
        private const val COL_STATUS_WIDTH = 80
        private const val COL_LAST_UPDATED_WIDTH = 100
        private const val COL_CREDITS_WIDTH = 120
    }

    init {
        title = "TokenPulse β Dashboard"
        setOKButtonText("Refresh All")
        setCancelButtonText("Close")
        init()

        // Initialize chart type combo
        chartTypeCombo.selectedItem = ChartType.LINE
        chartTypeCombo.renderer = ChartTypeRenderer()
        chartTypeCombo.addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED) {
                chartPanel.chartType = e.item as ChartType
            }
        }

        // Initialize time range combo
        timeRangeCombo.selectedItem = TimeRange.DAYS_7
        timeRangeCombo.renderer = TimeRangeRenderer()
        timeRangeCombo.addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED) {
                currentTimeRange = e.item as TimeRange
                chartPanel.selectedTimeRange = currentTimeRange
                refreshChartData()
            }
        }

        // Load initial data
        refreshChartData()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(JBUI.scale(DIALOG_WIDTH), JBUI.scale(DIALOG_HEIGHT))

        // Top: Controls panel
        panel.add(createControlsPanel(), BorderLayout.NORTH)

        // Center: Split pane with chart and table
        val splitPane = createMainContent()
        panel.add(splitPane, BorderLayout.CENTER)

        return panel
    }

    private fun createControlsPanel(): JPanel {
        val controlsPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), JBUI.scale(5)))
        controlsPanel.border = BorderFactory.createEmptyBorder(
            JBUI.scale(5), JBUI.scale(10), JBUI.scale(5), JBUI.scale(10)
        )

        // Chart type selector
        controlsPanel.add(JLabel("Chart:"))
        chartTypeCombo.preferredSize = Dimension(
            JBUI.scale(CHART_TYPE_COMBO_WIDTH),
            JBUI.scale(COMBO_HEIGHT)
        )
        controlsPanel.add(chartTypeCombo)

        // Time range selector
        controlsPanel.add(JLabel("Range:"))
        timeRangeCombo.preferredSize = Dimension(
            JBUI.scale(TIME_RANGE_COMBO_WIDTH),
            JBUI.scale(COMBO_HEIGHT)
        )
        controlsPanel.add(timeRangeCombo)

        // Refresh button
        val refreshButton = JButton("Refresh")
        refreshButton.addActionListener {
            performRefresh()
        }
        controlsPanel.add(refreshButton)

        return controlsPanel
    }

    private fun createMainContent(): JComponent {
        // Chart panel
        val chartContainer = JPanel(BorderLayout())
        chartContainer.border = BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(JBUI.scale(5), JBUI.scale(10), JBUI.scale(5), JBUI.scale(10)),
            BorderFactory.createTitledBorder("Balance History (% Remaining)")
        )
        chartPanel.preferredSize = Dimension(JBUI.scale(DIALOG_WIDTH - 40), JBUI.scale(CHART_HEIGHT))
        chartContainer.add(chartPanel, BorderLayout.CENTER)

        // Table panel
        val tablePanel = createTablePanel()

        // Split pane
        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, chartContainer, tablePanel)
        splitPane.resizeWeight = SPLIT_PANE_RESIZE_WEIGHT
        splitPane.dividerSize = JBUI.scale(5)
        splitPane.border = null

        return splitPane
    }

    private fun createTablePanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(JBUI.scale(5), JBUI.scale(10), JBUI.scale(10), JBUI.scale(10)),
            BorderFactory.createTitledBorder("Current Balances")
        )

        tableModel.items = TokenPulseSettingsService.getInstance().state.accounts.toMutableList()
        configureTable()

        val scrollPane = JBScrollPane(table)
        scrollPane.preferredSize = Dimension(JBUI.scale(DIALOG_WIDTH - 40), JBUI.scale(TABLE_HEIGHT))
        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    private fun configureTable() {
        table.fillsViewportHeight = true
        table.autoResizeMode = JBTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS

        val cm = table.columnModel
        cm.getColumn(COL_PROVIDER).preferredWidth = JBUI.scale(COL_PROVIDER_WIDTH)
        cm.getColumn(COL_KEY_PREVIEW).preferredWidth = JBUI.scale(COL_KEY_PREVIEW_WIDTH)
        cm.getColumn(COL_STATUS).preferredWidth = JBUI.scale(COL_STATUS_WIDTH)
        cm.getColumn(COL_LAST_UPDATED).preferredWidth = JBUI.scale(COL_LAST_UPDATED_WIDTH)
        cm.getColumn(COL_CREDITS).preferredWidth = JBUI.scale(COL_CREDITS_WIDTH)
    }

    private fun refreshChartData() {
        val historyService = BalanceHistoryService.getInstance()
        val accounts = TokenPulseSettingsService.getInstance().state.accounts

        // Get history data for the selected time range
        val historyByAccount = historyService.getAllHistory(currentTimeRange)

        // Update chart with data
        chartPanel.setData(historyByAccount, accounts)
    }

    private fun performRefresh() {
        BalanceRefreshService.getInstance().refreshAll(force = true)
        // Schedule UI update after a short delay
        ApplicationManager.getApplication().invokeLater {
            refreshChartData()
            tableModel.fireTableDataChanged()
        }
    }

    override fun createActions(): Array<Action> = arrayOf(okAction, cancelAction)

    override fun doOKAction() {
        performRefresh()
        // Don't close the dialog - user can press Close when done
    }

    /**
     * Custom renderer for ChartType combo box.
     */
    private class ChartTypeRenderer : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: javax.swing.JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): java.awt.Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is ChartType) {
                text = value.displayName
            }
            return component
        }
    }

    /**
     * Custom renderer for TimeRange combo box.
     */
    private class TimeRangeRenderer : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: javax.swing.JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): java.awt.Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is TimeRange) {
                text = value.displayName
            }
            return component
        }
    }
}
