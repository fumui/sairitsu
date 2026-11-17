package my.fumui.sairitsu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import my.fumui.sairitsu.gameplay.PlayResult
import my.fumui.sairitsu.gameplay.ReplayRef
import my.fumui.sairitsu.gameplay.RunRecord
import my.fumui.sairitsu.gameplay.RunSort
import my.fumui.sairitsu.gameplay.accuracyText
import my.fumui.sairitsu.gameplay.arcadeText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** When a run was played, to the minute in [zone] ("2026-11-16 21:04"); "—" for one from before history was kept. */
internal fun runDateText(at: Long?, zone: ZoneId = ZoneId.systemDefault()): String =
    at?.let { DATE.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "—"

/** Perfect / great / good / miss ("681 / 6 / 0 / 0"). */
internal fun judgmentText(r: PlayResult): String = "${r.perfect} / ${r.great} / ${r.good} / ${r.miss}"

/** What Watch needs to play [run] back; null when no replay was saved for it. */
internal fun replayRef(run: RunRecord): ReplayRef? =
    run.replay?.let { ReplayRef(it, run.levelData, run.engine) }

private val ACTION_SLOT = 48.dp

/** Rows shown before "Show all". */
private const val FIRST_ROWS = 20

/** The selected level's Play runs as a table, with a sort menu; nothing when there are no runs. */
@Composable
fun RunList(
    runs: List<RunRecord>,
    sort: RunSort,
    onSort: (RunSort) -> Unit,
    onWatchReplay: (RunRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (runs.isEmpty()) return
    var showAll by remember(runs) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your runs (${runs.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            RunSortButton(sort, onSort)
        }
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                RunRow("#", "Grade", "Arcade", "Accuracy", "P / Gr / Gd / M", "Combo", "Date", muted)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                val shown = if (showAll) runs else runs.take(FIRST_ROWS)
                shown.forEachIndexed { i, run ->
                    val r = run.result
                    RunRow(
                        "${i + 1}", r.grade.label, r.arcadeText(), r.accuracyText(), judgmentText(r), "${r.combo}",
                        runDateText(run.at), Color.Unspecified,
                        action = if (replayRef(run) != null) {
                            {
                                // Material's 48 dp touch target: rows with a replay are that tall.
                                IconButton(onClick = { onWatchReplay(run) }) {
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

@Composable
private fun RunRow(
    rank: String,
    grade: String,
    arcade: String,
    accuracy: String,
    judgments: String,
    combo: String,
    date: String,
    color: Color,
    action: (@Composable () -> Unit)? = null,
) {
    val style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cell(rank, 0.35f, style, color, TextAlign.End)
        Cell(grade, 1.1f, style, color, TextAlign.Start)
        Cell(arcade, 1f, style, color, TextAlign.End)
        Cell(accuracy, 1.1f, style, color, TextAlign.End)
        Cell(judgments, 1.5f, style, color, TextAlign.End)
        Cell(combo, 0.6f, style, color, TextAlign.End)
        Cell(date, 1.5f, style, color, TextAlign.End)
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
