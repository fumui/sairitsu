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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import my.fumui.sairitsu.data.model.LevelItem
import my.fumui.sairitsu.text.Localizer
import my.fumui.sairitsu.ui.Cover
import my.fumui.sairitsu.ui.RatingBadge
import java.util.Locale

// Always dark (the text is white): the page's base under the cover, and all of it when there is none.
private val Backdrop = Color(0xFF0E1218)
private val Gold = Color(0xFFFFC94D)
private val Cyan = Color(0xFF4DD8FF)
internal val JudgmentColors = listOf(Gold, Color(0xFFFF7EB6), Color(0xFF6BD68A), Color(0xFF8A8F98))
private val JudgmentLabels = listOf("Perfect", "Great", "Good", "Miss")
private val ResultTabLabels = listOf("Score", "Timing")

/**
 * Play's result page, full screen over the stage: the level ([level]: cover, title, artists, rating; left out
 * when null) beside two tabs. Score has the grade ("Quit" for a [partial] run), the arcade score with its difference
 * from the previous best, the accuracy score and combo, and the judgments as a bar and one row each. Timing has the
 * run's [timing]; without timed hits there are no tabs, only the score. Retry and Back (the system Back too) work from
 * the first frame; the content fades in and the score counts up.
 */
@Composable
fun ResultScreen(
    result: PlayResult,
    partial: Boolean,
    info: ResultInfo?,
    level: LevelItem?,
    timing: TimingUi?,
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
            val short = isShortResult(maxHeight)
            val pageHeight = resultPageHeight(maxHeight)
            // A bigger cover on tablets; 160 dp on a phone in landscape.
            val coverDp = (maxHeight.value * 0.4f).toInt().coerceIn(120, 280)
            AnimatedVisibility(
                visibleState = appear,
                enter = fadeIn(tween(250)) + slideInVertically(tween(250)) { it / 12 },
            ) {
                // The level and the tabs centred as one block, above the buttons.
                Row(
                    Modifier.fillMaxSize().padding(bottom = 56.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (short) 32.dp else 48.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val showLevel = wide && level != null
                    if (showLevel) {
                        Column(Modifier.weight(0.38f).verticalScroll(rememberScrollState())) {
                            LevelHeader(level, coverDp)
                        }
                    }
                    ResultTabs(
                        result, partial, info, timing, short, pageHeight,
                        if (showLevel) Modifier.weight(0.62f) else Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                    )
                }
            }
            if (!wide && level != null) {
                // No level column here: the title takes the free space beside the buttons.
                LevelTitle(level, Modifier.align(Alignment.BottomStart).padding(bottom = 10.dp).widthIn(max = maxWidth - 360.dp))
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

/** The level's title alone, where there is no room for its cover. */
@Composable
private fun LevelTitle(level: LevelItem, modifier: Modifier = Modifier) {
    val localizer = remember { Localizer(Localizer.serverLanguage(Locale.getDefault())) }
    Text(
        localizer.text(level.title),
        modifier,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
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

/** Whether the result page is too short to stack the grade above the score: a phone in landscape. */
fun isShortResult(height: Dp): Boolean = height < 480.dp

/** The height of a tab's page on a result page of [height], under the tabs and above the buttons. */
fun resultPageHeight(height: Dp): Dp = (height - 56.dp).coerceAtMost(560.dp) - 56.dp

/** The timing chart's height on a tab page of [pageHeight]: what the rows under it leave over. */
fun timingChartHeight(pageHeight: Dp): Dp = (pageHeight - 112.dp).coerceIn(68.dp, 260.dp)

/**
 * The run's Score page and, when it has timed hits, its Timing page, as tabs that also follow a swipe. Score opens
 * first. Both pages stay composed, so the score counts up only once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultTabs(
    result: PlayResult,
    partial: Boolean,
    info: ResultInfo?,
    timing: TimingUi?,
    short: Boolean,
    pageHeight: Dp,
    modifier: Modifier,
) {
    if (timing == null) {
        Column(modifier.verticalScroll(rememberScrollState())) { ScorePage(result, partial, info, short, pageHeight) }
        return
    }
    val pager = rememberPagerState { ResultTabLabels.size }
    val scope = rememberCoroutineScope()
    Column(modifier.height(pageHeight + 56.dp)) {
        PrimaryTabRow(
            selectedTabIndex = pager.currentPage,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            ResultTabLabels.forEachIndexed { i, label ->
                Tab(
                    selected = pager.currentPage == i,
                    onClick = { scope.launch { pager.animateScrollToPage(i) } },
                    text = { Text(label) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = Color.White.copy(alpha = 0.7f),
                )
            }
        }
        HorizontalPager(
            pager,
            Modifier.fillMaxWidth().weight(1f),
            beyondViewportPageCount = 1,
            verticalAlignment = Alignment.Top,
        ) { page ->
            // Clipped so a glyph at one page's edge does not show on the page beside it.
            Column(Modifier.fillMaxSize().clipToBounds().verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                if (page == 0) {
                    ScorePage(result, partial, info, short, pageHeight)
                } else {
                    TimingSection(timing, timingChartHeight(pageHeight))
                }
            }
        }
    }
}

/** The grade, the scores and the judgments. Where height is [short], the grade and the score share a line. */
@Composable
private fun ColumnScope.ScorePage(result: PlayResult, partial: Boolean, info: ResultInfo?, short: Boolean, pageHeight: Dp) {
    val muted = Color.White.copy(alpha = 0.7f)
    val roomy = !short || pageHeight >= 240.dp
    val gap = if (!short) 16.dp else if (roomy) 12.dp else 8.dp
    val rowGap = if (roomy) 2.dp else 0.dp
    val isNewBest = info?.isNewBest == true && !partial
    val best = info?.best?.takeIf { !info.isNewBest }
    val delta = bestDelta(result, info?.previousBest, partial)
    if (short) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GradeBadge(result, partial, MaterialTheme.typography.titleLarge)
            ScoreText(result)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                if (isNewBest) NewBestText(MaterialTheme.typography.titleSmall)
                if (best != null) BestText(best)
                if (delta != null) DeltaText(delta, MaterialTheme.typography.bodySmall)
            }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GradeBadge(result, partial, MaterialTheme.typography.headlineSmall)
            if (isNewBest) NewBestText(MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(gap))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ScoreText(result)
            if (delta != null) DeltaText(delta, MaterialTheme.typography.titleMedium, Modifier.padding(bottom = 6.dp))
        }
    }
    Text(
        "Accuracy ${result.accuracyText()} · Combo ${result.combo}",
        style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
        color = muted,
    )
    Spacer(Modifier.height(gap))
    JudgmentBar(result.judgmentFractions())
    Spacer(Modifier.height(gap * 0.75f))
    val counts = listOf(result.perfect, result.great, result.good, result.miss)
    val figures = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum", textAlign = TextAlign.End)
    counts.forEachIndexed { i, count ->
        Row(Modifier.fillMaxWidth().padding(vertical = rowGap), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(JudgmentColors[i]))
            Spacer(Modifier.width(8.dp))
            Text(JudgmentLabels[i], Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = muted)
            Text("$count", Modifier.width(64.dp), style = figures, fontWeight = FontWeight.Medium, color = Color.White)
            Text(result.percent(count), Modifier.width(72.dp), style = figures, fontWeight = FontWeight.Medium, color = Color.White)
        }
    }
    if (!short && best != null) {
        Spacer(Modifier.height(8.dp))
        BestText(best)
    }
}

@Composable
private fun GradeBadge(result: PlayResult, partial: Boolean, style: TextStyle) {
    val (label, color) = if (partial) "Quit" to Color.White.copy(alpha = 0.7f) else result.grade.label to gradeColor(result.grade)
    Surface(
        color = color.copy(alpha = 0.15f),
        border = BorderStroke(2.dp, color),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(
            label.uppercase(Locale.ROOT),
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = style,
            fontWeight = FontWeight.Black,
            color = color,
        )
    }
}

/** The arcade score, counting up from 0 once per run. */
@Composable
private fun ScoreText(result: PlayResult) {
    val shown = remember(result) { Animatable(0f) }
    LaunchedEffect(result) { shown.animateTo(result.arcadeScore.toFloat(), tween(600)) }
    Text(
        scoreText(shown.value.toInt()),
        style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
        fontWeight = FontWeight.Bold,
        color = Color.White,
    )
}

@Composable
private fun NewBestText(style: TextStyle) {
    Text("NEW BEST", style = style, fontWeight = FontWeight.Bold, color = Gold, maxLines = 1)
}

/** The stored best, when this run did not become it. */
@Composable
private fun BestText(best: PlayResult) {
    Text(
        "Best ${best.grade.label} ${best.arcadeText()}",
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.7f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun DeltaText(delta: String, style: TextStyle, modifier: Modifier = Modifier) {
    Text(
        "$delta vs best",
        modifier,
        style = style,
        color = if (delta.startsWith("+")) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
        maxLines = 1,
    )
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
