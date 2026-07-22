package org.zhavoronkov.tokenpulse.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.zhavoronkov.tokenpulse.model.Provider
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.service.BalanceRefreshService
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.settings.TokenPulseSettingsService
import org.zhavoronkov.tokenpulse.ui.TooltipModel.TooltipRow
import org.zhavoronkov.tokenpulse.utils.Constants
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.RenderingHints
import javax.swing.Box
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Builds the rich status-bar tooltip as a real Swing panel.
 *
 * This replaces the previous HTML tooltip (rendered by Swing's HTMLEditorKit),
 * which could not honor `white-space:nowrap` / `display:inline-block` and so
 * wrapped progress bars and their percentages onto separate lines. A
 * [GridBagLayout] guarantees the label, bar, and percent stay on one row and
 * that the panel sizes to its content; [com.intellij.ide.IdeTooltipManager]
 * clamps it to the screen.
 *
 * The per-provider branch logic mirrors the old HTML builder exactly; only the
 * leaf rendering changed from HTML strings to Swing components.
 */
object TokenPulseTooltipPanel {

    /** Progress bar width in device-independent pixels. */
    private const val BAR_WIDTH = 80

    /** Progress bar height in device-independent pixels. */
    private const val BAR_HEIGHT = 10

    /** Left indent (dip) of a connection label under its provider header. */
    private const val INDENT_ACCOUNT = 10

    /** Left indent (dip) of data rows under their connection label. */
    private const val INDENT_ROW = 20

    /** Top gap (dip) above every bar row; bars are never section headers. */
    private const val BAR_ROW_TOP_GAP = 2

    /** Empty/unfilled portion of a bar (matches ProgressBarRenderer). */
    private val COLOR_EMPTY = JBColor(Color(0xDDDDDD), Color(0x555555))

    private val COLOR_ERROR = JBColor(Color(0xCC4444), Color(0xFF7777))
    private val COLOR_WARNING = JBColor(Color(0xCC8800), Color(0xFFBB55))
    private val COLOR_OK = JBColor(Color(0x44AA44), Color(0x66DD66))

    /** Thin gray divider between account blocks. Same tone in light and dark. */
    private val COLOR_SEPARATOR = JBColor(Color(0x888888), Color(0x888888))

    /**
     * Builds a fresh tooltip component from the current settings and results.
     * Returns null when there is nothing to show (no enabled accounts / no data
     * yet); callers should fall back to a plain text tooltip in that case.
     */
    fun buildTooltip(): JComponent? {
        val accounts = TokenPulseSettingsService.getInstance().state.accounts
        val activeAccounts = accounts.filter { it.isEnabled }
        if (activeAccounts.isEmpty()) return null

        val results = BalanceRefreshService.getInstance().results.value
        val enabledAccountIds = activeAccounts.map { it.id }.toSet()
        val activeResults = results.filterKeys { it in enabledAccountIds }
        if (activeResults.isEmpty() || enabledAccountIds.none { it in results }) return null

        val successCount = activeResults.values.filterIsInstance<ProviderResult.Success>().count()
        val errorCount = activeResults.values.filterIsInstance<ProviderResult.Failure>().count()

        val panel = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.empty(6, 8)
            background = UIUtil.getToolTipBackground()
            isOpaque = true
        }
        val gbc = baseConstraints()

        addHeader(panel, gbc, successCount, errorCount)
        addSeparator(panel, gbc)

        // Group connections under a provider header so multiple connections of
        // the same provider (e.g. two Claude Code accounts) read as one block.
        TooltipModel.groupAccountsWithRows(activeAccounts, results)
            .forEach { (provider, accountsWithRows) ->
                addProviderSection(panel, gbc, provider, accountsWithRows)
            }

        addSeparator(panel, gbc)
        addFooter(panel, gbc)
        return panel
    }

    private fun baseConstraints(): GridBagConstraints = GridBagConstraints().apply {
        gridx = 0
        gridy = 0
        gridwidth = GridBagConstraints.REMAINDER
        weightx = 1.0
        anchor = GridBagConstraints.LINE_START
        fill = GridBagConstraints.HORIZONTAL
        insets = JBUI.emptyInsets()
    }

    private fun addHeader(panel: JPanel, gbc: GridBagConstraints, successCount: Int, errorCount: Int) {
        panel.add(
            JBLabel(Constants.DISPLAY_NAME).apply { font = JBFont.label().asBold() },
            gbc.nextRow()
        )
        val statusColor = if (errorCount > 0) COLOR_ERROR else COLOR_OK
        val errorSuffix = if (errorCount > 0) {
            " \u00b7 $errorCount error${if (errorCount > 1) "s" else ""}"
        } else {
            ""
        }
        panel.add(
            JBLabel("$successCount connected$errorSuffix").apply {
                font = JBFont.smallOrNewUiMedium()
                foreground = statusColor
            },
            gbc.nextRow(topGap = 1)
        )
    }

    private fun addSeparator(panel: JPanel, gbc: GridBagConstraints) {
        panel.add(
            JPanel().apply {
                background = COLOR_SEPARATOR
                isOpaque = true
                preferredSize = Dimension(1, JBUI.scale(1))
                minimumSize = Dimension(1, JBUI.scale(1))
            },
            gbc.nextRow(topGap = 4).apply { fill = GridBagConstraints.HORIZONTAL }
        )
    }

    private fun addFooter(panel: JPanel, gbc: GridBagConstraints) {
        panel.add(
            JBLabel("Click for Dashboard \u00b7 Refresh \u00b7 Settings").apply {
                font = JBFont.smallOrNewUiMedium()
                foreground = UIUtil.getContextHelpForeground()
            },
            gbc.nextRow(topGap = 4)
        )
    }

    /**
     * Emits a bold provider header (e.g. "Claude Code") followed by each
     * connection nested beneath. [accountsWithRows] is already filtered to
     * non-empty accounts by [TooltipModel.groupAccountsWithRows].
     */
    private fun addProviderSection(
        panel: JPanel,
        gbc: GridBagConstraints,
        provider: Provider,
        accountsWithRows: List<Pair<Account, List<TooltipRow>>>
    ) {
        panel.add(
            JBLabel(provider.displayName).apply { font = JBFont.label().asBold() },
            gbc.nextRow(topGap = 8)
        )
        accountsWithRows.forEach { (account, rows) -> addAccountSection(panel, gbc, account, rows) }
    }

    /**
     * Renders one connection under its provider header: a lighter connection
     * label indented one level, then the pre-built rows.
     */
    private fun addAccountSection(
        panel: JPanel,
        gbc: GridBagConstraints,
        account: Account,
        rows: List<TooltipRow>
    ) {
        // Connection-level label — provider name is already in the header, so
        // use the connection's own displayName (e.g. "Claude Code CLI", "API
        // Key") rather than the fullDisplayName which repeats the provider.
        val label = account.name.ifBlank { account.connectionType.displayName }
        panel.add(
            JBLabel(label).apply {
                font = JBFont.smallOrNewUiMedium().asBold()
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.emptyLeft(INDENT_ACCOUNT)
            },
            gbc.nextRow(topGap = 4)
        )
        rows.forEach { renderRow(panel, gbc, it) }
    }

    /**
     * Places [row]'s cells directly into the outer panel's shared grid at
     * fixed column indices so metric labels, bars, and percentages align
     * vertically across all rows. Row types that don't have inner columns
     * (info/error/section/header) span the whole width via
     * [GridBagConstraints.REMAINDER].
     *
     * Column plan:
     * - col 0: metric/field label (muted).
     * - col 1: bar OR value; the bar has a fixed size so all bars share the
     *   same X origin and width.
     * - col 2: percent, right-aligned (LINE_END) so digits line up.
     * - col 3: filler (weightx = 1.0) OR the optional inline reset text.
     */
    private fun renderRow(panel: JPanel, gbc: GridBagConstraints, row: TooltipRow) {
        val topGap = when (row) {
            is TooltipRow.SectionHeader -> 4
            else -> 2
        }
        when (row) {
            is TooltipRow.LabelValue -> {
                cell(
                    panel,
                    gridx = 0,
                    gridy = nextGridy(gbc),
                    topGap = topGap,
                    leftInset = INDENT_ROW,
                    anchor = GridBagConstraints.LINE_START
                ) {
                    JBLabel(row.label).apply {
                        foreground = UIUtil.getContextHelpForeground()
                        border = JBUI.Borders.emptyRight(8)
                    }
                }
                // Value spans cols 1..3 so long text (emails, tenant names)
                // uses the bar+percent+filler space; keep weightx=1 so this
                // row still gives the grid a trailing filler.
                cell(
                    panel,
                    gridx = 1,
                    gridy = gbc.gridy,
                    topGap = topGap,
                    gridwidth = 3,
                    weightx = 1.0,
                    anchor = GridBagConstraints.LINE_START,
                    fill = GridBagConstraints.HORIZONTAL
                ) {
                    JBLabel(row.value).apply {
                        if (row.bold) font = font.deriveFont(java.awt.Font.BOLD)
                    }
                }
            }
            is TooltipRow.UsageBar -> addBarRow(
                panel,
                gbc,
                row.label,
                row.fillPercent,
                row.labelText,
                ProgressBarRenderer.getUsageColor(row.fillPercent),
                row.resetInline
            )
            is TooltipRow.BalanceBar -> addBarRow(
                panel,
                gbc,
                row.label,
                row.remainingPercent,
                "${row.remainingPercent.coerceIn(0, 100)}%",
                ProgressBarRenderer.getBalanceColor(row.remainingPercent),
                row.resetInline
            )
            is TooltipRow.Info -> panel.add(
                JBLabel(row.message).apply {
                    font = JBFont.smallOrNewUiMedium()
                    foreground = UIUtil.getContextHelpForeground()
                },
                gbc.nextRow(topGap = topGap, leftInset = INDENT_ROW)
            )
            is TooltipRow.Error -> panel.add(
                JBLabel(row.message).apply {
                    font = JBFont.smallOrNewUiMedium()
                    foreground = if (row.warning) COLOR_WARNING else COLOR_ERROR
                },
                gbc.nextRow(topGap = topGap, leftInset = INDENT_ROW)
            )
            is TooltipRow.SectionHeader -> panel.add(
                JBLabel(row.title).apply {
                    font = JBFont.label().asBold()
                    foreground = UIUtil.getContextHelpForeground()
                },
                gbc.nextRow(topGap = topGap, leftInset = INDENT_ROW)
            )
        }
    }

    /**
     * Adds a bar row into the shared grid so label/bar/percent columns line
     * up with every other bar and LabelValue row. When [resetInline] is
     * present it occupies col 3; otherwise col 3 is an empty filler that
     * keeps the grid left-packed.
     */
    private fun addBarRow(
        panel: JPanel,
        gbc: GridBagConstraints,
        label: String,
        fillPercent: Int,
        labelText: String,
        color: Color,
        resetInline: String?
    ) {
        val topGap = BAR_ROW_TOP_GAP
        val row = nextGridy(gbc)
        cell(
            panel,
            gridx = 0,
            gridy = row,
            topGap = topGap,
            leftInset = INDENT_ROW,
            anchor = GridBagConstraints.LINE_START
        ) {
            JBLabel(label).apply {
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.emptyRight(8)
            }
        }
        cell(
            panel,
            gridx = 1,
            gridy = row,
            topGap = topGap,
            anchor = GridBagConstraints.LINE_START,
            rightInset = 4
        ) {
            UsageBar(fillPercent, color)
        }
        cell(panel, gridx = 2, gridy = row, topGap = topGap, anchor = GridBagConstraints.LINE_END) {
            JBLabel(labelText).apply {
                font = font.deriveFont(java.awt.Font.BOLD)
            }
        }
        if (!resetInline.isNullOrBlank()) {
            cell(
                panel,
                gridx = 3,
                gridy = row,
                topGap = topGap,
                weightx = 1.0,
                anchor = GridBagConstraints.LINE_START,
                fill = GridBagConstraints.HORIZONTAL,
                leftInset = 6
            ) {
                JBLabel(resetInline).apply {
                    font = JBFont.smallOrNewUiMedium()
                    foreground = UIUtil.getContextHelpForeground()
                }
            }
        } else {
            // Empty filler in col 3 so the grid stays left-packed and cols 0-2
            // hug their content.
            cell(
                panel,
                gridx = 3,
                gridy = row,
                topGap = topGap,
                weightx = 1.0,
                fill = GridBagConstraints.HORIZONTAL
            ) { Box.createHorizontalGlue() }
        }
    }

    /**
     * Advance the grid y-cursor without emitting a full-width row. Used by
     * bar and LabelValue rows that place multiple cells on the same y.
     */
    private fun nextGridy(gbc: GridBagConstraints): Int {
        gbc.gridy += 1
        return gbc.gridy
    }

    /** Place a single component into the shared grid at explicit coordinates. */
    private fun cell(
        panel: JPanel,
        gridx: Int,
        gridy: Int,
        topGap: Int = 0,
        gridwidth: Int = 1,
        weightx: Double = 0.0,
        anchor: Int = GridBagConstraints.LINE_START,
        fill: Int = GridBagConstraints.NONE,
        leftInset: Int = 0,
        rightInset: Int = 0,
        component: () -> Component
    ) {
        val c = GridBagConstraints().apply {
            this.gridx = gridx
            this.gridy = gridy
            this.gridwidth = gridwidth
            this.weightx = weightx
            this.anchor = anchor
            this.fill = fill
            this.insets = Insets(
                JBUI.scale(topGap), JBUI.scale(leftInset), 0, JBUI.scale(rightInset)
            )
        }
        panel.add(component(), c)
    }

    /** Advance [this] to the next grid row, optionally adding a top gap and left indent. */
    private fun GridBagConstraints.nextRow(topGap: Int = 0, leftInset: Int = 0): GridBagConstraints {
        gridx = 0
        gridy += 1
        gridwidth = GridBagConstraints.REMAINDER
        weightx = 1.0
        anchor = GridBagConstraints.LINE_START
        fill = GridBagConstraints.HORIZONTAL
        insets = Insets(JBUI.scale(topGap), JBUI.scale(leftInset), 0, 0)
        return this
    }

    /** A theme-aware, custom-painted usage/balance bar. */
    private class UsageBar(percent: Int, private val fill: Color) : JComponent() {
        private val clamped = percent.coerceIn(0, 100)

        init {
            val size = JBUI.size(BAR_WIDTH, BAR_HEIGHT)
            preferredSize = size
            minimumSize = size
            maximumSize = size
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = JBUI.scale(3)
                g2.color = COLOR_EMPTY
                g2.fillRoundRect(0, 0, width, height, arc, arc)
                val filled = width * clamped / 100
                if (filled > 0) {
                    g2.color = fill
                    g2.fillRoundRect(0, 0, filled, height, arc, arc)
                }
            } finally {
                g2.dispose()
            }
        }
    }
}
