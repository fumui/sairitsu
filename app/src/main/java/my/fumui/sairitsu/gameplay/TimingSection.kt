package my.fumui.sairitsu.gameplay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

private val Muted = Color.White.copy(alpha = 0.7f)
private const val TICK_STEP_MS = 50

/** What the result screen's timing section shows and can do. */
class TimingUi(val stats: TimingStats, val canEdit: Boolean, val apply: OffsetApply, val onApply: () -> Unit)

/** The Timing tab: the chart, the run's average hit error and unstable rate, and the suggested input offset change. */
@Composable
fun TimingSection(ui: TimingUi, chartHeight: Dp) = BoxWithConstraints(Modifier.fillMaxWidth()) {
    val stats = ui.stats
    val compact = maxWidth < 440.dp
    val body = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TimingChart(stats, chartHeight)
        StatLine("▼ Average hit error", stats.averageText, body)
        StatLine("Unstable rate", stats.unstableRate.roundToInt().toString(), body)
        if (ui.canEdit && stats.count >= TimingStats.MIN_HITS_TO_APPLY) {
            if (stats.correctionMs == 0) {
                Text("Your timing is centred", style = body, color = Muted)
            } else {
                ApplyRow(stats.correctionMs, ui.apply, ui.onApply, body, compact)
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String, style: TextStyle) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = style, color = Muted)
        Text(
            value,
            style = style.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.Medium,
            color = Color.White,
        )
    }
}

@Composable
private fun ApplyRow(correctionMs: Int, apply: OffsetApply, onApply: () -> Unit, style: TextStyle, compact: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (compact) "Suggested change" else "Suggested input offset change",
            Modifier.weight(1f),
            style = style,
            color = Muted,
        )
        Text(
            "%+d ms".format(Locale.ROOT, correctionMs),
            style = style.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.Medium,
            color = Color.White,
        )
        Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
        OutlinedButton(
            onClick = onApply,
            enabled = apply == OffsetApply.Idle,
            contentPadding = if (compact) PaddingValues(horizontal = 12.dp) else ButtonDefaults.ContentPadding,
        ) {
            if (apply == OffsetApply.Applied) {
                Icon(Icons.Default.Check, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(if (compact) 4.dp else 8.dp))
                Text("Applied")
            } else {
                Text("Apply")
            }
        }
    }
}

/**
 * Bars of timed hits by error, stacked Perfect, Great, Good from the baseline in the judgment rows' colours, with a line
 * at 0, a marker above the average, and "Early" and "Late" in the top corners.
 */
@Composable
private fun TimingChart(stats: TimingStats, height: Dp) {
    val measurer = rememberTextMeasurer()
    val tickStyle = MaterialTheme.typography.labelSmall.copy(color = Muted, fontFeatureSettings = "tnum")
    val sideStyle = MaterialTheme.typography.labelMedium.copy(color = Muted)
    val description = "Timing chart of ${stats.count} timed hits. Average hit error ${stats.averageText}, " +
        "unstable rate ${stats.unstableRate.roundToInt()}."
    Canvas(Modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        val inset = 16.dp.toPx()
        val plotTop = 10.dp.toPx()
        val plotBottom = size.height - 20.dp.toPx()
        val halfWidth = stats.halfWidthMs.toFloat()
        val xOf = { ms: Float -> size.width / 2 + ms / halfWidth * (size.width / 2 - inset) }

        val slot = (size.width - inset * 2) / (stats.bins.size - 1)
        val barWidth = (slot - 1.dp.toPx()).coerceIn(1.5.dp.toPx(), 8.dp.toPx())
        val gap = 1.dp.toPx()
        val minSegment = 2.dp.toPx()
        val unit = (plotBottom - plotTop - gap * 2 - minSegment) / stats.bins.maxOf { it.total }
        val corner = CornerRadius(1.5.dp.toPx())
        for (bin in stats.bins) {
            var bottom = plotBottom
            for ((count, color) in listOf(bin.perfect, bin.great, bin.good).zip(JudgmentColors)) {
                if (count == 0) continue
                val height = max(count * unit, minSegment)
                drawRoundRect(
                    color,
                    Offset(xOf(bin.centerMs.toFloat()) - barWidth / 2, bottom - height),
                    Size(barWidth, height),
                    corner,
                )
                bottom -= height + gap
            }
        }

        drawLine(Color.White.copy(alpha = 0.25f), Offset(0f, plotBottom), Offset(size.width, plotBottom), 1.dp.toPx())
        drawLine(Color.White.copy(alpha = 0.6f), Offset(xOf(0f), plotTop), Offset(xOf(0f), plotBottom), 1.dp.toPx())

        val averageX = xOf(stats.averageMs.toFloat().coerceIn(-halfWidth, halfWidth))
        val markerHalf = 5.dp.toPx()
        drawPath(
            Path().apply {
                moveTo(averageX - markerHalf, 0f)
                lineTo(averageX + markerHalf, 0f)
                lineTo(averageX, plotTop - 1.dp.toPx())
                close()
            },
            Color.White,
        )

        drawText(measurer.measure("Early", sideStyle), topLeft = Offset.Zero)
        val late = measurer.measure("Late", sideStyle)
        drawText(late, topLeft = Offset(size.width - late.size.width, 0f))

        for (ms in -stats.halfWidthMs..stats.halfWidthMs step TICK_STEP_MS) {
            val label = measurer.measure(if (ms > 0) "+$ms" else "$ms", tickStyle)
            drawText(label, topLeft = Offset(xOf(ms.toFloat()) - label.size.width / 2f, plotBottom + 4.dp.toPx()))
        }
    }
}

/** Asks where to save the suggested input offset, showing each scope's change. */
@Composable
fun ApplyOffsetDialog(dialog: OffsetDialog, onApply: (OffsetScope) -> Unit, onDismiss: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf(OffsetScope.Level) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply input offset") },
        text = {
            Column(Modifier.selectableGroup()) {
                Text(correctionText(dialog.correctionMs))
                Spacer(Modifier.height(12.dp))
                for (choice in dialog.choices) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = choice.scope == selected,
                                role = Role.RadioButton,
                                onClick = { selected = choice.scope },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice.scope == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(choice.scope.label, Modifier.weight(1f))
                        Text(
                            "${offsetText(choice.current)} → ${offsetText(choice.suggested)}",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(selected) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
