package my.fumui.sairitsu.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import my.fumui.sairitsu.gameplay.PlayResult
import my.fumui.sairitsu.gameplay.ReplayRef
import my.fumui.sairitsu.gameplay.RunRecord
import my.fumui.sairitsu.gameplay.RunSort
import my.fumui.sairitsu.gameplay.accuracyText
import my.fumui.sairitsu.gameplay.arcadeText
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS

/** Days after which a run shows its date instead of "<n>d ago". */
private const val RELATIVE_DAYS = 28

/**
 * When a run was played, from [now]: "just now" (under a minute, or a clock set back), "5m ago", "3h ago", "3d ago",
 * then the date in [zone] from 4 weeks on ("2026-10-02"); "—" for one from before history was kept.
 */
internal fun runAgoText(at: Long?, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (at == null) return "—"
    val ago = now - at
    return when {
        ago < MINUTE_MS -> "just now"
        ago < HOUR_MS -> "${ago / MINUTE_MS}m ago"
        ago < DAY_MS -> "${ago / HOUR_MS}h ago"
        ago < RELATIVE_DAYS * DAY_MS -> "${ago / DAY_MS}d ago"
        else -> DAY.format(Instant.ofEpochMilli(at).atZone(zone))
    }
}

/** The one score the table shows: the accuracy score when sorting by it, else the arcade score. */
internal fun runScoreText(r: PlayResult, sort: RunSort): String =
    if (sort == RunSort.Accuracy) r.accuracyText() else r.arcadeText()

/** The score column's header for [sort]. */
internal fun runScoreHeader(sort: RunSort): String = if (sort == RunSort.Accuracy) "Accuracy" else "Arcade"

/** What Watch needs to play [run] back; null when no replay was saved for it. */
internal fun replayRef(run: RunRecord): ReplayRef? =
    run.replay?.let { ReplayRef(it, run.levelData, run.engine) }

/** The ▶ slot's width and a data row's height: compact, since a tap anywhere else on the row opens the run. */
private val ACTION_SLOT = 40.dp

/** Rows shown before "Show all". */
private const val FIRST_ROWS = 20

/** How often the Time column re-reads the clock while it is on screen. */
private const val CLOCK_TICK_MS = 30_000L

/**
 * The selected level's Play runs as a table (#, grade, combo, the sorted score, how long ago, replay) with a sort
 * menu; nothing when there are no runs. A row tap opens the run ([onOpenRun]); its ▶ watches the replay.
 */
@Composable
fun RunList(
    runs: List<RunRecord>,
    sort: RunSort,
    onSort: (RunSort) -> Unit,
    onWatchReplay: (RunRecord) -> Unit,
    onOpenRun: (RunRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (runs.isEmpty()) return
    var showAll by remember(runs) { mutableStateOf(false) }
    val now = rememberNow()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your runs (${runs.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            RunSortButton(sort, onSort)
        }
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                RunRow("#", "Grade", "Combo", runScoreHeader(sort), "Time", muted, minHeight = 0.dp)
                HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                val shown = if (showAll) runs else runs.take(FIRST_ROWS)
                shown.forEachIndexed { i, run ->
                    val r = run.result
                    RunRow(
                        "${i + 1}", r.grade.label, "${r.combo}", runScoreText(r, sort), runAgoText(run.at, now),
                        Color.Unspecified,
                        modifier = Modifier.clickable { onOpenRun(run) },
                        action = if (replayRef(run) != null) {
                            {
                                // It takes its own taps, so the row doesn't open.
                                IconButton(onClick = { onWatchReplay(run) }, modifier = Modifier.size(ACTION_SLOT)) {
                                    Icon(
                                        Icons.Filled.PlayArrow,
                                        contentDescription = "Watch replay",
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
                if (!showAll && runs.size > FIRST_ROWS) {
                    TextButton(onClick = { showAll = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Show all ${runs.size}")
                    }
                }
            }
        }
    }
}

/** The wall clock in ms, re-read every [CLOCK_TICK_MS] while composed, so "just now" doesn't stay for an hour. */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CLOCK_TICK_MS)
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
private fun RunRow(
    rank: String,
    grade: String,
    combo: String,
    score: String,
    time: String,
    color: Color,
    modifier: Modifier = Modifier,
    minHeight: Dp = ACTION_SLOT,
    action: (@Composable () -> Unit)? = null,
) {
    val style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
    Row(
        // Data rows are as tall as the ▶ slot, with or without a replay, so a mixed list stays even.
        modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 1.dp).heightIn(min = minHeight),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cell(rank, 0.35f, style, color, TextAlign.End)
        Cell(grade, 1.1f, style, color, TextAlign.Start)
        Cell(combo, 0.6f, style, color, TextAlign.End)
        Cell(score, 1f, style, color, TextAlign.End)
        Cell(time, 1f, style, color, TextAlign.End)
        Box(Modifier.width(ACTION_SLOT), contentAlignment = Alignment.Center) { action?.invoke() }
    }
}

@Composable
private fun RowScope.Cell(text: String, weight: Float, style: TextStyle, color: Color, align: TextAlign) {
    Text(
        text,
        modifier = Modifier.weight(weight),
        style = style,
        color = color,
        textAlign = align,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The current sort's name; opens a menu of the three sorts. */
@Composable
private fun RunSortButton(sort: RunSort, onSort: (RunSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SortButtonFace(sort.label, descending = null) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (option in RunSort.entries) {
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = { RadioButton(selected = option == sort, onClick = null) },
                    onClick = {
                        onSort(option)
                        open = false
                    },
                )
            }
        }
    }
}
