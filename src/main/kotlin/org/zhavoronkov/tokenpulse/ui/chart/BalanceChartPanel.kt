package org.zhavoronkov.tokenpulse.ui.chart

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import org.zhavoronkov.tokenpulse.model.BalanceHistoryEntry
import org.zhavoronkov.tokenpulse.model.ChartType
import org.zhavoronkov.tokenpulse.model.TimeRange
import org.zhavoronkov.tokenpulse.settings.Account
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.geom.Path2D
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JPanel
import javax.swing.ToolTipManager

/**
 * Custom panel for rendering balance history as a percentage-based chart.
 *
 * All data is displayed on a unified 0-100% scale, making it easy to compare
 * different accounts regardless of whether they track dollars or percentages.
 *
 * Features:
 * - Multiple account lines with distinct colors
 * - Hover tooltips showing exact values
 * - Grid lines and axis labels
 * - Line or area chart styles
 * - Theme-aware colors
 */
class BalanceChartPanel : JPanel() {

    /** History data grouped by account */
    private var data: Map<String, List<BalanceHistoryEntry>> = emptyMap()

    /** Account info for display names and colors */
    private var accounts: Map<String, Account> = emptyMap()

    /** Selected time range for X-axis bounds */
    var selectedTimeRange: TimeRange = TimeRange.DAYS_7
        set(value) {
            field = value
            repaint()
        }

    /** Current chart type */
    var chartType: ChartType = ChartType.LINE
        set(value) {
            field = value
            repaint()
        }

    /** Current mouse position for hover effects */
    private var mousePosition: Point? = null

    /** Provider colors for consistent visual identity */
    private val providerColors = listOf(
        JBColor(Color(66, 133, 244), Color(100, 160, 255)),   // Blue (OpenRouter)
        JBColor(Color(52, 168, 83), Color(80, 200, 110)),    // Green (Cline)
        JBColor(Color(251, 188, 4), Color(255, 210, 60)),    // Yellow (OpenAI)
        JBColor(Color(234, 67, 53), Color(255, 100, 90)),    // Red (Nebius)
        JBColor(Color(154, 87, 220), Color(180, 120, 255)),  // Purple (Claude)
        JBColor(Color(255, 109, 0), Color(255, 140, 60))     // Orange (ChatGPT)
    )

    init {
        isOpaque = true
        preferredSize = Dimension(JBUI.scale(600), JBUI.scale(250))

        ToolTipManager.sharedInstance().registerComponent(this)

        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                mousePosition = e.point
                updateTooltip(e.point)
                repaint()
            }
        })

        addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                mousePosition = null
                toolTipText = null
                repaint()
            }
        })
    }

    /**
     * Updates the chart with new data.
     *
     * @param historyByAccount Map of accountId to history entries.
     * @param accountList List of accounts for display info.
     */
    fun setData(historyByAccount: Map<String, List<BalanceHistoryEntry>>, accountList: List<Account>) {
        this.data = historyByAccount
        this.accounts = accountList.associateBy { it.id }
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        // Background
        g2.color = JBColor(Color(252, 252, 252), Color(45, 45, 45))
        g2.fillRect(0, 0, width, height)

        val bounds = ChartBounds(
            left = JBUI.scale(50),
            top = JBUI.scale(20),
            right = width - JBUI.scale(20),
            bottom = height - JBUI.scale(50)
        )

        if (data.isEmpty() || data.values.all { it.isEmpty() }) {
            drawNoDataMessage(g2, bounds)
            return
        }

        drawGrid(g2, bounds)
        drawAxes(g2, bounds)
        drawData(g2, bounds)
        drawLegend(g2, bounds)
        mousePosition?.let { drawHoverLine(g2, bounds, it) }
    }

    private fun drawNoDataMessage(g2: Graphics2D, bounds: ChartBounds) {
        g2.color = JBColor(Color(120, 120, 120), Color(150, 150, 150))
        g2.font = Font(Font.SANS_SERIF, Font.ITALIC, JBUI.scale(14))
        val message = "No history data yet. Data will appear after the first refresh."
        val metrics = g2.fontMetrics
        val x = bounds.left + (bounds.width - metrics.stringWidth(message)) / 2
        val y = bounds.top + bounds.height / 2
        g2.drawString(message, x, y)
    }

    private fun drawGrid(g2: Graphics2D, bounds: ChartBounds) {
        g2.color = JBColor(Color(230, 230, 230), Color(60, 60, 60))
        g2.stroke = BasicStroke(1f)

        // Horizontal grid lines (0%, 25%, 50%, 75%, 100%)
        for (i in 0..4) {
            val y = bounds.top + (bounds.height * i / 4)
            g2.drawLine(bounds.left, y, bounds.right, y)
        }

        // Vertical grid lines (time-based)
        for (i in 0..4) {
            val x = bounds.left + (bounds.width * i / 4)
            g2.drawLine(x, bounds.top, x, bounds.bottom)
        }
    }

    private fun drawAxes(g2: Graphics2D, bounds: ChartBounds) {
        g2.color = JBColor(Color(100, 100, 100), Color(180, 180, 180))
        g2.stroke = BasicStroke(2f)

        // Y-axis
        g2.drawLine(bounds.left, bounds.top, bounds.left, bounds.bottom)

        // X-axis
        g2.drawLine(bounds.left, bounds.bottom, bounds.right, bounds.bottom)

        // Y-axis labels (percentages)
        g2.font = Font(Font.SANS_SERIF, Font.PLAIN, JBUI.scale(10))
        val labels = listOf("100%", "75%", "50%", "25%", "0%")
        labels.forEachIndexed { index, label ->
            val y = bounds.top + (bounds.height * index / 4)
            val metrics = g2.fontMetrics
            g2.drawString(label, bounds.left - metrics.stringWidth(label) - JBUI.scale(5), y + metrics.ascent / 2)
        }

        // X-axis labels (time)
        drawTimeLabels(g2, bounds)
    }

    /**
     * Gets the time bounds for the chart based on the selected time range.
     * Always uses the full selected range regardless of when data points exist.
     *
     * @return Pair of (startTime, endTime) in epoch seconds
     */
    private fun getTimeBounds(): Pair<Long, Long> {
        val now = Instant.now()
        val maxTime = now.epochSecond
        val minTime = when (selectedTimeRange) {
            TimeRange.ALL -> {
                // For "All Time", use the earliest data point or default to 7 days ago
                val earliestData = data.values.flatten().minOfOrNull { it.timestamp.epochSecond }
                earliestData ?: (now.epochSecond - 7 * 24 * 3600)
            }
            else -> selectedTimeRange.getStartInstant().epochSecond
        }
        return minTime to maxTime
    }

    private fun drawTimeLabels(g2: Graphics2D, bounds: ChartBounds) {
        val (minTime, maxTime) = getTimeBounds()
        val timeRange = maxTime - minTime
        if (timeRange <= 0) return

        // Choose date format based on time range
        val formatter = when {
            timeRange > 86400 * 7 -> DateTimeFormatter.ofPattern("MMM d")   // More than a week: show date
            timeRange > 86400 -> DateTimeFormatter.ofPattern("EEE HH:mm")   // 1-7 days: show day + time
            else -> DateTimeFormatter.ofPattern("HH:mm")                     // Less than a day: show time only
        }.withZone(ZoneId.systemDefault())

        g2.font = Font(Font.SANS_SERIF, Font.PLAIN, JBUI.scale(10))

        for (i in 0..4) {
            val time = minTime + (timeRange * i / 4)
            val instant = Instant.ofEpochSecond(time)
            val label = formatter.format(instant)

            val x = bounds.left + (bounds.width * i / 4)
            val metrics = g2.fontMetrics
            g2.drawString(label, x - metrics.stringWidth(label) / 2, bounds.bottom + JBUI.scale(15))
        }
    }

    private fun drawData(g2: Graphics2D, bounds: ChartBounds) {
        val allPoints = data.values.flatten()
        if (allPoints.isEmpty()) return

        // Use the full time range from the selected filter
        val (minTime, maxTime) = getTimeBounds()
        val timeRange = maxTime - minTime
        if (timeRange <= 0) return

        data.entries.forEachIndexed { index, (accountId, entries) ->
            if (entries.isEmpty()) return@forEachIndexed

            val color = providerColors[index % providerColors.size]
            val points = entries.map { entry ->
                val x = bounds.left + ((entry.timestamp.epochSecond - minTime).toDouble() / timeRange * bounds.width).toInt()
                val y = bounds.bottom - (entry.percentageRemaining / 100.0 * bounds.height).toInt()
                Point(x, y)
            }

            when (chartType) {
                ChartType.LINE -> drawLine(g2, points, color)
                ChartType.AREA -> drawArea(g2, points, color, bounds)
            }
        }
    }

    private fun drawLine(g2: Graphics2D, points: List<Point>, color: Color) {
        if (points.isEmpty()) return

        g2.color = color
        g2.stroke = BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

        for (i in 0 until points.size - 1) {
            g2.drawLine(points[i].x, points[i].y, points[i + 1].x, points[i + 1].y)
        }

        // Draw points
        points.forEach { point ->
            g2.fillOval(point.x - 3, point.y - 3, 6, 6)
        }
    }

    private fun drawArea(g2: Graphics2D, points: List<Point>, color: Color, bounds: ChartBounds) {
        if (points.isEmpty()) return

        // Draw filled area
        val path = Path2D.Float()
        path.moveTo(points.first().x.toFloat(), bounds.bottom.toFloat())
        points.forEach { path.lineTo(it.x.toFloat(), it.y.toFloat()) }
        path.lineTo(points.last().x.toFloat(), bounds.bottom.toFloat())
        path.closePath()

        g2.color = Color(color.red, color.green, color.blue, 50)
        g2.fill(path)

        // Draw line on top
        drawLine(g2, points, color)
    }

    private fun drawLegend(g2: Graphics2D, bounds: ChartBounds) {
        if (data.isEmpty()) return

        g2.font = Font(Font.SANS_SERIF, Font.PLAIN, JBUI.scale(11))
        val metrics = g2.fontMetrics

        var x = bounds.left
        val y = bounds.bottom + JBUI.scale(35)

        data.keys.forEachIndexed { index, accountId ->
            val account = accounts[accountId]
            val color = providerColors[index % providerColors.size]
            val name = account?.displayLabel() ?: accountId.take(8)

            // Color box
            g2.color = color
            g2.fillRect(x, y - JBUI.scale(10), JBUI.scale(14), JBUI.scale(14))

            // Name
            g2.color = JBColor(Color(60, 60, 60), Color(200, 200, 200))
            g2.drawString(name, x + JBUI.scale(18), y + JBUI.scale(2))

            x += metrics.stringWidth(name) + JBUI.scale(35)
        }
    }

    private fun drawHoverLine(g2: Graphics2D, bounds: ChartBounds, mouse: Point) {
        if (mouse.x < bounds.left || mouse.x > bounds.right) return

        g2.color = JBColor(Color(150, 150, 150, 150), Color(200, 200, 200, 150))
        g2.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(5f), 0f)
        g2.drawLine(mouse.x, bounds.top, mouse.x, bounds.bottom)
    }

    private fun updateTooltip(point: Point) {
        val bounds = ChartBounds(
            left = JBUI.scale(50),
            top = JBUI.scale(20),
            right = width - JBUI.scale(20),
            bottom = height - JBUI.scale(50)
        )

        if (point.x < bounds.left || point.x > bounds.right) {
            toolTipText = null
            return
        }

        // Find nearest point
        var nearest: Pair<String, BalanceHistoryEntry>? = null
        var minDist = Int.MAX_VALUE

        val allPoints = data.values.flatten()
        if (allPoints.isEmpty()) {
            toolTipText = null
            return
        }

        // Use the full time range from selected filter (same as drawData)
        val (minTime, maxTime) = getTimeBounds()
        val timeRange = maxTime - minTime
        if (timeRange <= 0) {
            toolTipText = null
            return
        }

        data.forEach { (accountId, entries) ->
            entries.forEach { entry ->
                val x = bounds.left + ((entry.timestamp.epochSecond - minTime).toDouble() / timeRange * bounds.width).toInt()
                val y = bounds.bottom - (entry.percentageRemaining / 100.0 * bounds.height).toInt()
                val dist = Math.abs(point.x - x) + Math.abs(point.y - y)

                if (dist < minDist && dist < JBUI.scale(40)) {
                    minDist = dist
                    nearest = accountId to entry
                }
            }
        }

        nearest?.let { (accountId, entry) ->
            val account = accounts[accountId]
            val name = account?.displayLabel() ?: accountId.take(8)
            val timeStr = DateTimeFormatter.ofPattern("MMM d, HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(entry.timestamp)

            toolTipText = "<html><b>$name</b><br>$timeStr<br>${entry.percentageRemaining.toInt()}% (${entry.rawValue} ${entry.rawUnit})</html>"
        } ?: run {
            toolTipText = null
        }
    }

    private data class ChartBounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }
}
