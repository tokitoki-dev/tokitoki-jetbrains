package dev.tokitoki.jetbrains.toolwindow

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.tokitoki.jetbrains.TokitokiService
import dev.tokitoki.jetbrains.cli.StatsDaily
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import javax.swing.JComponent

// One color per identity, everywhere it appears: time is always green, AI
// tokens always blue. A chart never mixes the two, so no legend is needed.
val TIME_COLOR = JBColor(0x2E8B57, 0x89D185)
val AI_COLOR = JBColor(0x1F6FD0, 0x3794FF)

/** The recessed track a bar sits in, and the tile background. */
private val TRACK_COLOR: Color get() = JBColor(0xEBECF0, 0x3C3F41)

private fun Graphics2D.smooth() {
    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
}

/**
 * The past week as bars, one per day, labelled with the weekday. Height is
 * directly comparable — twice as tall is twice as long. Today is the bar the
 * reader is looking for, so it alone is at full strength and its label is
 * not dimmed. Hovering a bar names its day.
 */
class DailyChart(private val days: List<StatsDaily>) : JComponent() {
    init {
        preferredSize = Dimension(JBUI.scale(180), JBUI.scale(72 + 18))
        minimumSize = Dimension(JBUI.scale(100), JBUI.scale(60))
        toolTipText = ""
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.smooth()
            if (days.isEmpty()) return
            g2.font = JBFont.small().deriveFont(JBFont.small().size2D - 1)
            val metrics = g2.fontMetrics
            val labelHeight = metrics.height + JBUI.scale(4)
            val chartHeight = height - labelHeight
            val max = days.maxOf { it.active_seconds }.coerceAtLeast(1)
            val gap = JBUI.scale(4)
            val slot = (width - gap * (days.size - 1)).toDouble() / days.size
            val minBar = JBUI.scale(2)
            val radius = JBUI.scale(2)

            days.forEachIndexed { index, day ->
                val isToday = index == days.lastIndex
                val x = (index * (slot + gap)).toInt()
                val w = slot.toInt().coerceAtLeast(JBUI.scale(4))
                val h = (((chartHeight - minBar) * day.active_seconds) / max).toInt() + minBar
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, if (isToday) 1f else 0.55f)
                g2.color = TIME_COLOR
                g2.fillRoundRect(x, chartHeight - h, w, h + radius, radius, radius)
                g2.fillRect(x, chartHeight - radius, w, radius)
                g2.composite = AlphaComposite.SrcOver

                val label = weekdayInitial(day.date)
                g2.color = if (isToday) UIUtil.getLabelForeground() else UIUtil.getContextHelpForeground()
                g2.drawString(label, x + (w - metrics.stringWidth(label)) / 2, height - metrics.descent)
            }
        } finally {
            g2.dispose()
        }
    }

    override fun getToolTipText(event: MouseEvent): String? {
        if (days.isEmpty()) return null
        val index = (event.x * days.size / width.coerceAtLeast(1)).coerceIn(0, days.lastIndex)
        val day = days[index]
        return "${day.date} · ${TokitokiService.formatDuration(day.active_seconds)} · ${TokitokiService.formatTokens(day.total_tokens)}"
    }

    private fun weekdayInitial(date: String): String =
        try {
            LocalDate.parse(date).dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())
        } catch (error: Exception) {
            date.takeLast(2)
        }
}

/**
 * One ranked entry: name and value on a line, then a bar scaled against the
 * group's leader. A second, slimmer bar underneath carries a second measure
 * when there is one (tokens under time), so the row still reads as one entry
 * with a primary measure rather than two competing charts.
 */
class RankedRow(
    private val name: String,
    private val value: String,
    private val primary: Double,
    private val primaryColor: Color,
    private val secondary: Double? = null,
    private val secondaryColor: Color = AI_COLOR,
) : JComponent() {
    private val track = JBUI.scale(4)
    private val thin = JBUI.scale(3)

    init {
        val text = JBFont.regular().size + JBUI.scale(6)
        val bars = track + if (secondary != null) thin + JBUI.scale(2) else 0
        preferredSize = Dimension(JBUI.scale(180), text + bars)
        minimumSize = Dimension(JBUI.scale(80), text + bars)
        toolTipText = "$name · $value"
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.smooth()
            g2.font = JBFont.regular()
            val metrics = g2.fontMetrics
            val baseline = metrics.ascent
            val valueWidth = metrics.stringWidth(value)
            g2.color = UIUtil.getContextHelpForeground()
            g2.drawString(value, width - valueWidth, baseline)
            g2.color = UIUtil.getLabelForeground()
            g2.drawString(ellipsize(name, width - valueWidth - JBUI.scale(8), g2), 0, baseline)

            var y = metrics.height + JBUI.scale(3)
            bar(g2, y, track, primary, primaryColor)
            if (secondary != null) {
                y += track + JBUI.scale(2)
                bar(g2, y, thin, secondary, secondaryColor)
            }
        } finally {
            g2.dispose()
        }
    }

    private fun bar(g2: Graphics2D, y: Int, h: Int, fraction: Double, color: Color) {
        val r = h / 2
        g2.color = TRACK_COLOR
        g2.fillRoundRect(0, y, width, h, r, r)
        if (fraction <= 0) return
        // Floored so a nonzero value always leaves a visible mark.
        val w = (width * fraction.coerceIn(0.02, 1.0)).toInt().coerceAtLeast(h)
        g2.color = color
        g2.fillRoundRect(0, y, w, h, r, r)
    }

    private fun ellipsize(text: String, maxWidth: Int, g2: Graphics2D): String {
        val metrics = g2.fontMetrics
        if (metrics.stringWidth(text) <= maxWidth) return text
        var cut = text
        while (cut.isNotEmpty() && metrics.stringWidth("$cut…") > maxWidth) cut = cut.dropLast(1)
        return "$cut…"
    }
}

/** One of the headline numbers: the value large, the label small and dim,
 * in a recessed box. The value is colored by what it measures. */
class Tile(value: String, label: String, color: Color?) : JComponent() {
    private val valueLabel = com.intellij.ui.components.JBLabel(value).apply {
        font = JBFont.label().deriveFont(java.awt.Font.BOLD, JBFont.label().size2D + 4)
        if (color != null) foreground = color
    }
    private val captionLabel = com.intellij.ui.components.JBLabel(label).apply {
        font = JBFont.small()
        foreground = UIUtil.getContextHelpForeground()
    }

    init {
        layout = java.awt.BorderLayout(0, JBUI.scale(2))
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(8, 10),
        )
        isOpaque = true
        background = TRACK_COLOR
        add(valueLabel, java.awt.BorderLayout.CENTER)
        add(captionLabel, java.awt.BorderLayout.SOUTH)
    }
}
