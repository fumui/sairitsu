package my.fumui.sairitsu.gameplay

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import my.fumui.sairitsu.data.model.LevelItem
import my.fumui.sairitsu.text.Localizer
import my.fumui.sairitsu.ui.Cover
import my.fumui.sairitsu.ui.RatingBadge
import java.util.Locale

// Always dark (the text is white): the page's base under the cover, and all of it when there is none.
private val Backdrop = Color(0xFF0E1218)
private val Gold = Color(0xFFFFC94D)
private val Cyan = Color(0xFF4DD8FF)
private val JudgmentColors = listOf(Gold, Color(0xFFFF7EB6), Color(0xFF6BD68A), Color(0xFF8A8F98))
private val JudgmentLabels = listOf("Perfect", "Great", "Good", "Miss")

/**
 * Play's result page, full screen over the stage: the level ([level]: cover, title, artists, rating; left out
 * when null), the grade ("Quit" for a [partial] run), the arcade score with its difference from the previous best, the
 * accuracy score and combo, and the judgments as a bar and counts. Retry and Back (the system Back too) work from the
 * first frame; the content fades in and the score counts up.
 */
@Composable
fun ResultScreen(
    result: PlayResult,
    partial: Boolean,
    info: ResultInfo?,
    level: LevelItem?,
    replaySave: ReplaySave,
    onSaveReplay: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Box(
        Modifier
            .fillMaxSize()
            .background(Backdrop)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        val cover = level?.cover?.url
        if (cover != null) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(32.dp) else Modifier),
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.65f)))
        }
        val appear = remember { MutableTransitionState(false).apply { targetState = true } }
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp)) {
            val wide = maxWidth >= 600.dp
            // A bigger cover on tablets; 160 dp on a phone in landscape.
            val coverDp = (maxHeight.value * 0.4f).toInt().coerceIn(120, 280)
            AnimatedVisibility(
                visibleState = appear,
                enter = fadeIn(tween(250)) + slideInVertically(tween(250)) { it / 12 },
            ) {
                if (wide && level == null) {
                    // No level to show: the stats alone, centred.
                    Box(Modifier.fillMaxSize().padding(bottom = 56.dp), contentAlignment = Alignment.Center) {
                        Column(Modifier.widthIn(max = 640.dp).verticalScroll(rememberScrollState())) {
                            Stats(result, partial, info)
                        }
                    }
                } else if (wide) {
                    // Both columns centred as one block; the stats scroll when they do not fit above the buttons.
                    Row(
                        Modifier.fillMaxSize().padding(bottom = 56.dp),
                        horizontalArrangement = Arrangement.spacedBy(48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(0.4f)) {
                            if (level != null) LevelHeader(level, coverDp)
                        }
                        Column(Modifier.weight(0.6f).verticalScroll(rememberScrollState())) {
                            Stats(result, partial, info)
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 56.dp)) {
                        if (level != null) {
                            LevelHeader(level, coverDp)
                            Spacer(Modifier.height(24.dp))
                        }
                        Stats(result, partial, info)
                    }
                }
            }
            Row(Modifier.align(Alignment.BottomEnd), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SaveReplayButton(replaySave, onSaveReplay)
                Button(onClick = onRetry) { Text("Retry") }
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
        }
    }
}

/** "Save replay": enabled only when the replay is ready; hidden when there is none (a quit, or no file to save). */
@Composable
private fun SaveReplayButton(state: ReplaySave, onSave: () -> Unit) {
    when (state) {
        ReplaySave.Unavailable, is ReplaySave.Failed -> Unit
        ReplaySave.Saved -> OutlinedButton(onClick = {}, enabled = false) {
            Icon(Icons.Default.Check, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Saved")
        }
        else -> OutlinedButton(onClick = onSave, enabled = state == ReplaySave.Ready) { Text("Save replay") }
    }
}

@Composable
private fun LevelHeader(level: LevelItem, coverDp: Int) {
    val localizer = remember { Localizer(Localizer.serverLanguage(Locale.getDefault())) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Cover(level.cover?.url, coverDp)
        RatingBadge(level.rating)
        Text(
            localizer.text(level.title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val artists = localizer.text(level.artists)
        if (artists.isNotBlank()) {
            Text(
                artists,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ColumnScope.Stats(result: PlayResult, partial: Boolean, info: ResultInfo?) {
    val scheme = MaterialTheme.colorScheme
    val muted = Color.White.copy(alpha = 0.7f)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val (label, color) = if (partial) "Quit" to muted else result.grade.label to gradeColor(result.grade)
        Surface(
            color = color.copy(alpha = 0.15f),
            border = BorderStroke(2.dp, color),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text(
                label.uppercase(Locale.ROOT),
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color = color,
            )
        }
        if (info?.isNewBest == true && !partial) {
            Text("NEW BEST", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Gold)
        }
    }
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val shown = remember(result) { Animatable(0f) }
        LaunchedEffect(result) { shown.animateTo(result.arcadeScore.toFloat(), tween(600)) }
        Text(
            scoreText(shown.value.toInt()),
            style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        bestDelta(result, info?.previousBest, partial)?.let { delta ->
            Text(
                "$delta vs best",
                Modifier.padding(bottom = 6.dp),
                style = MaterialTheme.typography.titleMedium,
                color = if (delta.startsWith("+")) scheme.primary else muted,
            )
        }
    }
    Text(
        "Accuracy ${result.accuracyText()} · Combo ${result.combo}",
        style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
        color = muted,
    )
    Spacer(Modifier.height(16.dp))
    JudgmentBar(result.judgmentFractions())
    Spacer(Modifier.height(12.dp))
    val counts = listOf(result.perfect, result.great, result.good, result.miss)
    for (rowStart in listOf(0, 2)) {
        Row(Modifier.fillMaxWidth()) {
            for (i in rowStart..rowStart + 1) {
                Row(Modifier.weight(1f).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(JudgmentColors[i]))
                    Spacer(Modifier.width(8.dp))
                    Text(JudgmentLabels[i], Modifier.width(64.dp), style = MaterialTheme.typography.bodyLarge, color = muted)
                    Text(
                        "${counts[i]}  ${result.percent(counts[i])}",
                        style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                    )
                }
            }
        }
    }
    val best = info?.best
    if (best != null && !info.isNewBest) {
        Spacer(Modifier.height(8.dp))
        Text("Best ${best.grade.label} ${best.arcadeText()}", style = MaterialTheme.typography.bodyMedium, color = muted)
    }
}

@Composable
private fun JudgmentBar(fractions: List<Float>) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.12f)),
    ) {
        fractions.forEachIndexed { i, f ->
            if (f > 0f) Box(Modifier.weight(f).fillMaxSize().background(JudgmentColors[i]))
        }
    }
}

@Composable
private fun gradeColor(grade: Grade): Color = when (grade) {
    Grade.AllPerfect -> Gold
    Grade.FullCombo -> Cyan
    Grade.Pass -> MaterialTheme.colorScheme.primary
    Grade.Fail -> MaterialTheme.colorScheme.error
}
