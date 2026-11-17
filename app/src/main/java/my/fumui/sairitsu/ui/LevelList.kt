package my.fumui.sairitsu.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.ui.state.ToggleableState
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import my.fumui.sairitsu.data.model.LevelItem
import my.fumui.sairitsu.text.Localizer
import kotlin.math.absoluteValue

/** How many screens of rows to keep loaded below the viewport, so a fling rarely reaches the end first. */
private const val PREFETCH_SCREENS = 3
/** Lower bound on the prefetch distance, for before the list is laid out. */
private const val MIN_PREFETCH_ROWS = 10

/** Text drawn on the bright difficulty and accent colors. */
val OnBadge = Color(0xFF10161C)

/** True when the next page should start loading: the end is within [PREFETCH_SCREENS] screens of the viewport. */
internal fun shouldLoadMore(lastVisible: Int, visibleCount: Int, size: Int, loading: Boolean): Boolean {
    if (size == 0 || loading) return false
    val distance = maxOf(MIN_PREFETCH_ROWS, visibleCount * PREFETCH_SCREENS)
    return lastVisible >= size - distance
}

/**
 * What the list watches to decide when to load more: null while no more is needed, otherwise a key that
 * changes with every page added. A cached page can load and append within one frame, so watching only
 * [shouldLoadMore]'s true/false would miss that it is still true afterwards and stop paging.
 */
internal fun loadMoreKey(lastVisible: Int, visibleCount: Int, groups: Int, list: ListState): String? =
    if (shouldLoadMore(lastVisible, visibleCount, groups, list.loading)) "${list.items.size}|${list.page}|${list.cursor}" else null

/** Multi-select for [LevelList]: long-press starts it; while [active], taps toggle a row's charts or one chart. */
class LevelSelection(
    val active: Boolean,
    val isSelected: (LevelItem) -> Boolean,
    /** Long-press while not [active]: a row's charts, or one chart. */
    val onStart: (List<LevelItem>) -> Unit,
    /** Tap (or long-press) while [active]. */
    val onToggle: (List<LevelItem>) -> Unit,
)

/** The level list: one row per song, its difficulties as colored pills (see [groupLevels]). */
@Composable
fun LevelList(
    list: ListState,
    selectedName: String?,
    localizer: Localizer,
    onSelect: (LevelItem) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** Names of the levels that are downloaded; their pills and covers show a check mark. */
    downloaded: Set<String> = emptySet(),
    /** Row keys; the Play list keys by server too, since two servers may use the same level name. */
    itemKey: (Int, LevelItem) -> Any = { _, level -> level.name },
    /** Shown when there are no levels and no error. */
    emptyText: String = "No levels match these filters",
    /** Multi-select (long-press to start); null: none. */
    selection: LevelSelection? = null,
) {
    val listState = rememberLazyListState()
    val groups = remember(list.items) { groupLevels(list.items) }
    val currentGroups by rememberUpdatedState(groups)
    val currentList by rememberUpdatedState(list)
    val loadMore by rememberUpdatedState(onLoadMore)

    LaunchedEffect(listState) {
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            // The footer is an extra row: an index past the groups means the end is on screen.
            loadMoreKey(visible.lastOrNull()?.index ?: -1, visible.size, currentGroups.size, currentList)
        }.collect { if (it != null) loadMore() }
    }
    LaunchedEffect(list.items.firstOrNull()?.name) { listState.scrollToItem(0) }

    Box(modifier) {
        when {
            list.items.isEmpty() && list.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            list.items.isEmpty() && list.error != null -> ErrorMessage(list.error, onRetry, Modifier.align(Alignment.Center))
            list.items.isEmpty() -> Text(
                emptyText,
                Modifier.align(Alignment.Center).padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(groups, key = { itemKey(it.firstIndex, it.first) }) { group ->
                    LevelGroupRow(group, selectedName, localizer, downloaded, onSelect, selection)
                }
                item(key = "footer") { ListFooter(list, onRetry) }
            }
        }
    }
}

@Composable
private fun LevelGroupRow(
    group: LevelGroup,
    selectedName: String?,
    localizer: Localizer,
    downloaded: Set<String>,
    onSelect: (LevelItem) -> Unit,
    selection: LevelSelection?,
) {
    val selected = group.levels.firstOrNull { it.name == selectedName }
    val level = selected ?: group.first
    val selecting = selection?.active == true
    val picked = if (selecting) group.levels.count { selection.isSelected(it) } else 0
    val background = when {
        selecting && picked > 0 -> MaterialTheme.colorScheme.secondaryContainer
        !selecting && selected != null -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val accent = selected?.takeIf { !selecting }?.let { Difficulty.of(it, localizer)?.color ?: MaterialTheme.colorScheme.primary }
    val click = when {
        selection == null -> Modifier.clickable { onSelect(level) }
        selecting -> Modifier.combinedClickable(
            onLongClick = { selection.onToggle(group.levels) },
            onClick = { selection.onToggle(group.levels) },
        )
        else -> Modifier.combinedClickable(onLongClick = { selection.onStart(group.levels) }, onClick = { onSelect(level) })
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .background(background)
            .drawBehind { if (accent != null) drawRect(accent, size = Size(3.dp.toPx(), size.height)) }
            .then(click)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            TriStateCheckbox(
                state = when (picked) {
                    0 -> ToggleableState.Off
                    group.levels.size -> ToggleableState.On
                    else -> ToggleableState.Indeterminate
                },
                onClick = { selection.onToggle(group.levels) },
            )
        } else {
            Spacer(Modifier.width(10.dp))
        }
        CoverTitleAndPills(Modifier.weight(1f)) {
            Box {
                // The layout gives the box a fixed square; fillMaxSize makes the cover take all of it.
                Cover(level.cover?.url, 64, Modifier.fillMaxSize(), title = localizer.text(level.title))
                if (group.levels.any { it.name in downloaded }) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                        modifier = Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp).size(16.dp),
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = "Downloaded", tint = OnBadge, modifier = Modifier.padding(2.dp))
                    }
                }
            }
            Column {
                Text(
                    localizer.text(level.title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    byline(level, localizer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                group.byRating.forEach { (_, chart) ->
                    DifficultyPill(
                        chart,
                        localizer,
                        selected = if (selecting) selection.isSelected(chart) else chart.name == selectedName,
                        downloaded = chart.name in downloaded,
                        onClick = { if (selecting) selection.onToggle(listOf(chart)) else onSelect(chart) },
                        onLongClick = selection?.let { s ->
                            { if (s.active) s.onToggle(listOf(chart)) else s.onStart(listOf(chart)) }
                        },
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
    }
}

/** Narrowest the title may get beside the pills before they move to their own line under it. */
private val MIN_INLINE_TITLE = 140.dp
/** Width of a full set of pills (six two-digit ratings), the most a song usually has. */
private val FULL_PILL_SET = 236.dp // 6 pills × 36dp + 5 gaps × 4dp
private val PILLS_INLINE_GAP = 8.dp
private val PILLS_STACKED_GAP = 6.dp
private val COVER_GAP = 12.dp
private val MIN_COVER = 48.dp

/**
 * Lays out its three children, the cover, the title column and the pill row. The title and pills sit side
 * by side (pills at the end) when the title keeps at least [MIN_INLINE_TITLE] beside a full set of pills,
 * otherwise the pills go on a line of their own under the title, so a narrow list pane (a phone's) still
 * shows readable titles. Rows with few pills reserve a full set's width, so every row in a pane picks the
 * same layout. The cover is a square as tall as the text and pills beside it, at least [MIN_COVER].
 */
@Composable
private fun CoverTitleAndPills(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val (cover, title, pills) = measurables
        val width = constraints.maxWidth
        val pillsWidth = maxOf(pills.maxIntrinsicWidth(constraints.maxHeight), FULL_PILL_SET.roundToPx())
        val titleHeight = title.minIntrinsicHeight(width)
        val pillsHeight = pills.minIntrinsicHeight(pillsWidth)
        val inlineGap = PILLS_INLINE_GAP.roundToPx()
        val stackedGap = PILLS_STACKED_GAP.roundToPx()
        val coverGap = COVER_GAP.roundToPx()
        val inlineCover = maxOf(MIN_COVER.roundToPx(), titleHeight, pillsHeight)
        val inline = width - inlineCover - coverGap - pillsWidth - inlineGap >= MIN_INLINE_TITLE.roundToPx()
        val coverSize = if (inline) inlineCover else maxOf(MIN_COVER.roundToPx(), titleHeight + stackedGap + pillsHeight)
        val c = cover.measure(Constraints.fixed(coverSize, coverSize))
        val textX = coverSize + coverGap
        val textWidth = (width - textX).coerceAtLeast(0)
        val loose = Constraints(maxWidth = textWidth)
        if (inline) {
            val p = pills.measure(loose)
            val t = title.measure(Constraints(maxWidth = (textWidth - p.width - inlineGap).coerceAtLeast(0)))
            val height = maxOf(coverSize, t.height, p.height).coerceAtLeast(constraints.minHeight)
            layout(width, height) {
                c.placeRelative(0, (height - c.height) / 2)
                t.placeRelative(textX, (height - t.height) / 2)
                p.placeRelative(width - p.width, (height - p.height) / 2)
            }
        } else {
            val t = title.measure(loose)
            val p = pills.measure(loose)
            val textHeight = t.height + stackedGap + p.height
            val height = maxOf(coverSize, textHeight).coerceAtLeast(constraints.minHeight)
            val top = (height - textHeight) / 2
            layout(width, height) {
                c.placeRelative(0, (height - c.height) / 2)
                t.placeRelative(textX, top)
                p.placeRelative(textX, top + t.height + stackedGap)
            }
        }
    }
}

/** A chart's rating in its difficulty's color: solid when [selected], tinted otherwise. */
@Composable
fun DifficultyPill(
    level: LevelItem,
    localizer: Localizer,
    selected: Boolean,
    downloaded: Boolean = false,
    /** Null: no long-press (the pill is only tappable). */
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val color = Difficulty.of(level, localizer)?.color ?: MaterialTheme.colorScheme.primary
    Surface(
        color = if (selected) color else color.copy(alpha = 0.16f),
        contentColor = if (selected) OnBadge else color,
        shape = RoundedCornerShape(7.dp),
        modifier = Modifier.clip(RoundedCornerShape(7.dp)).combinedClickable(onLongClick = onLongClick, onClick = onClick),
    ) {
        Row(
            Modifier.widthIn(min = 32.dp).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (downloaded) {
                Icon(Icons.Filled.Check, contentDescription = "Downloaded", Modifier.size(11.dp))
                Spacer(Modifier.width(2.dp))
            }
            Text(
                formatRating(level.rating),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Resolves server-relative resource URLs against the connected server. */
val LocalUrlResolver = staticCompositionLocalOf<(String?) -> String?> { { it } }

/**
 * A rounded cover image. While loading it pulses; with no image (or a failed one) it shows [title]'s
 * first letter on a tile colored from the title, so missing covers still tell rows apart.
 */
@Composable
fun Cover(url: String?, sizeDp: Int, modifier: Modifier = Modifier, title: String? = null) {
    val model = LocalUrlResolver.current(url)
    var state by remember(model) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    val shape = RoundedCornerShape((sizeDp / 8).coerceAtLeast(4).dp)
    val tile = title?.let(::titleColor) ?: MaterialTheme.colorScheme.surfaceVariant
    Box(modifier.size(sizeDp.dp).clip(shape).background(tile), contentAlignment = Alignment.Center) {
        when {
            state is AsyncImagePainter.State.Loading -> LoadingPulse()
            (model == null || state is AsyncImagePainter.State.Error) && !title.isNullOrBlank() -> Text(
                title.trim().take(1).uppercase(),
                color = Color.White.copy(alpha = 0.85f),
                fontWeight = FontWeight.Bold,
                fontSize = (sizeDp * 0.42f).sp,
            )
        }
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onState = { state = it },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun LoadingPulse() {
    val alpha by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.02f,
        targetValue = 0.10f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = alpha)))
}

/** A muted color picked from [title], stable across launches. */
private fun titleColor(title: String): Color {
    val hue = (title.hashCode().absoluteValue % 360).toFloat()
    return Color.hsl(hue, 0.35f, 0.28f)
}

/** The rating in [difficulty]'s color, or the theme's primary color when it has none. */
@Composable
fun RatingBadge(rating: Double, difficulty: Difficulty? = null) {
    Surface(color = difficulty?.color ?: MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(6.dp)) {
        Text(
            formatRating(rating),
            Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = OnBadge,
        )
    }
}

@Composable
fun ErrorMessage(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun ListFooter(list: ListState, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
        when {
            list.loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            list.error != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(list.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRetry) { Text("Retry") }
            }
            list.endReached -> Text(
                "End of list",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

fun byline(level: LevelItem, localizer: Localizer): String =
    listOf(localizer.text(level.artists), localizer.text(level.author))
        .filter { it.isNotBlank() }
        .joinToString(" · ")
