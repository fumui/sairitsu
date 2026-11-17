package my.fumui.sairitsu.ui

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import my.fumui.sairitsu.audio.PreviewStatus
import my.fumui.sairitsu.data.model.ItemDetails
import my.fumui.sairitsu.data.model.LevelItem
import my.fumui.sairitsu.data.model.ServerJson
import my.fumui.sairitsu.data.model.Tag
import my.fumui.sairitsu.play.GameplayFeatures
import my.fumui.sairitsu.text.DescriptionBlock
import my.fumui.sairitsu.text.Localizer
import my.fumui.sairitsu.text.parseDescription

/** An entry in the details pane's ⋮ menu. */
class MenuAction(val label: String, val onClick: () -> Unit)

@Composable
fun DetailsPane(
    selected: LevelItem?,
    details: Loadable<ItemDetails>,
    localizer: Localizer,
    preview: PreviewStatus,
    onLaunch: (LaunchTarget, LevelItem) -> Unit,
    onPreview: (String) -> Unit,
    onSelect: (LevelItem) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** More buttons after the launch buttons (Download). */
    extraActions: @Composable () -> Unit = {},
    /** Entries of the ⋮ menu (Delete); no menu when empty. */
    menuActions: List<MenuAction> = emptyList(),
    /** The detail sections (`#SAME_AUTHOR` ...); off for downloaded levels, which can't browse the server. */
    showSections: Boolean = true,
    /** Shown under the header (the Play list's server and best result). */
    footer: @Composable () -> Unit = {},
    /** Shown under the details (the Play list's runs). */
    bottom: @Composable () -> Unit = {},
) {
    if (selected == null) return
    // The pane shows [selected]'s cover and media URLs (local files in the Play list), the details only add to it.
    val level = (details as? Loadable.Ready)?.value?.item?.takeIf { it.name == selected.name && showSections } ?: selected

    // Fade the pane in when the selection changes.
    val fade = remember { Animatable(1f) }
    LaunchedEffect(selected.name) {
        fade.snapTo(0f)
        fade.animateTo(1f, tween(220))
    }

    val coverAccent = rememberCoverAccent(level.cover?.url)
    val accent by animateColorAsState(coverAccent ?: MaterialTheme.colorScheme.primary, tween(400), label = "accent")
    val scheme = MaterialTheme.colorScheme.copy(primary = accent, onPrimary = OnBadge)

    MaterialTheme(colorScheme = scheme) {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).alpha(fade.value)) {
            Box(Modifier.fillMaxWidth()) {
                Backdrop(level.cover?.url, Modifier.matchParentSize())
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)) {
                    Header(level, localizer, preview, onLaunch, onPreview, extraActions, menuActions)
                    footer()
                }
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(8.dp))
                when (details) {
                    Loadable.Idle -> Unit
                    Loadable.Loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    is Loadable.Error -> ErrorMessage(details.message, onRetry)
                    is Loadable.Ready -> DetailsBody(details.value, level, localizer, onSelect, showSections)
                }
                bottom()
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

/** The cover, blurred and faded into the background behind the header. */
@Composable
private fun Backdrop(url: String?, modifier: Modifier) {
    val model = LocalUrlResolver.current(url) ?: return
    val surface = MaterialTheme.colorScheme.surface
    val blurs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    Box(modifier) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().then(if (blurs) Modifier.blur(48.dp) else Modifier).alpha(if (blurs) 0.55f else 0.18f),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(surface.copy(alpha = 0.35f), surface.copy(alpha = 0.75f), surface)),
            ),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(
    level: LevelItem,
    localizer: Localizer,
    preview: PreviewStatus,
    onLaunch: (LaunchTarget, LevelItem) -> Unit,
    onPreview: (String) -> Unit,
    extraActions: @Composable () -> Unit,
    menuActions: List<MenuAction>,
) {
    val title = localizer.text(level.title)
    val difficulty = Difficulty.of(level, localizer)
    Row {
        Box {
            Cover(level.cover?.url, 148, title = title)
            val url = LocalUrlResolver.current(level.preview?.url ?: level.bgm?.url)
            if (url != null) {
                val active = (preview as? PreviewStatus.Playing)?.url == url || (preview as? PreviewStatus.Loading)?.url == url
                PreviewButton(active, Modifier.align(Alignment.BottomEnd).padding(8.dp)) { onPreview(url) }
            }
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (level.artists.isNotBlank()) {
                Text(localizer.text(level.artists), style = MaterialTheme.typography.titleMedium)
            }
            val credit = listOfNotNull(
                level.author.takeIf { it.isNotBlank() }?.let { "by ${localizer.text(it)}" },
                level.engine?.let { localizer.text(it.title) },
            ).joinToString(" · ")
            if (credit.isNotEmpty()) {
                Text(credit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RatingBadge(level.rating, difficulty)
                level.tags.forEach { TagChip(it, localizer) }
            }
            Spacer(Modifier.height(14.dp))
            // The ⋮ menu stays at the end of the first button row instead of wrapping onto a line of its own.
            Row(verticalAlignment = Alignment.Top) {
            FlowRow(
                Modifier.weight(1f, fill = false),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (GameplayFeatures.PLAY_ENABLED) {
                    LaunchButton("Play", Icons.Outlined.PlayArrow, primary = true) { onLaunch(LaunchTarget.Play, level) }
                    LaunchButton("Watch", Icons.Outlined.Visibility, primary = false) { onLaunch(LaunchTarget.Watch, level) }
                } else {
                    LaunchButton("Watch", Icons.Outlined.Visibility, primary = true) { onLaunch(LaunchTarget.Watch, level) }
                }
                OutlinedIconButton(onClick = { onLaunch(LaunchTarget.LevelOptions, level) }) {
                    Icon(Icons.Outlined.Tune, contentDescription = "Level options")
                }
                extraActions()
            }
            if (menuActions.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                OverflowMenu(menuActions)
            }
            }
        }
    }
}

@Composable
private fun LaunchButton(label: String, icon: ImageVector, primary: Boolean, onClick: () -> Unit) {
    val content: @Composable () -> Unit = {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
    if (primary) {
        Button(onClick = onClick, modifier = Modifier.widthIn(min = 120.dp)) { content() }
    } else {
        OutlinedButton(onClick = onClick) { content() }
    }
}

/** The round play/stop button over the cover that toggles the audio preview. */
@Composable
private fun PreviewButton(active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.6f),
        contentColor = Color.White,
        modifier = modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (active) Icons.Filled.Stop else Icons.Filled.Headphones,
                contentDescription = if (active) "Stop preview" else "Preview",
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Which of the details pane's launchers a button asks for. */
enum class LaunchTarget { Watch, Play, LevelOptions }

@Composable
private fun OverflowMenu(actions: List<MenuAction>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "More")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label) },
                    onClick = {
                        open = false
                        action.onClick()
                    },
                )
            }
        }
    }
}

@Composable
private fun DetailsBody(
    details: ItemDetails,
    level: LevelItem,
    localizer: Localizer,
    onSelect: (LevelItem) -> Unit,
    showSections: Boolean,
) {
    val title = localizer.text(level.title)
    val blocks = remember(details.description, localizer) {
        details.description?.let { parseDescription(it, localizer, title) }.orEmpty()
    }
    if (blocks.isNotEmpty()) {
        Description(blocks)
        Spacer(Modifier.height(20.dp))
    }
    if (showSections) details.sections.forEach { section ->
        val levels = section.levels(ServerJson)
        if (levels.isEmpty()) return@forEach
        Text(localizer.text(section.title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        // Other difficulties and versions share this level's cover and title: chips say more than covers.
        if (levels.all { localizer.text(it.title) == title }) {
            ChartChips(levels, level, localizer, onSelect)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(levels, key = { it.name }) { item ->
                    val itemTitle = localizer.text(item.title)
                    Column(Modifier.width(88.dp).clickable { onSelect(item) }) {
                        Cover(item.cover?.url, 88, title = itemTitle)
                        Spacer(Modifier.height(4.dp))
                        Text(itemTitle, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** The description's fields as a label/value table, its paragraphs as text. */
@Composable
private fun Description(blocks: List<DescriptionBlock>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        var i = 0
        while (i < blocks.size) {
            val block = blocks[i]
            if (block is DescriptionBlock.Paragraph) {
                Text(block.text, style = MaterialTheme.typography.bodyMedium)
                i++
                continue
            }
            val fields = blocks.drop(i).takeWhile { it is DescriptionBlock.Field }.map { it as DescriptionBlock.Field }
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    fields.forEach { field ->
                        Row {
                            Text(
                                field.label,
                                Modifier.width(110.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(field.value, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            i += fields.size
        }
    }
}

/** Charts of the same song: difficulty-colored chips, with the artists when they differ from [current]'s. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartChips(levels: List<LevelItem>, current: LevelItem, localizer: Localizer, onSelect: (LevelItem) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        levels.sortedBy { it.rating }.forEach { item ->
            val difficulty = Difficulty.of(item, localizer)
            val color = difficulty?.color ?: MaterialTheme.colorScheme.primary
            val label = listOfNotNull(
                difficulty?.name,
                item.artists.takeIf { it != current.artists }?.let(localizer::text),
            ).joinToString(" · ")
            Surface(
                onClick = { onSelect(item) },
                color = color.copy(alpha = 0.14f),
                contentColor = color,
                shape = RoundedCornerShape(10.dp),
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RatingBadge(item.rating, difficulty)
                    if (label.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            label,
                            Modifier.widthIn(max = 260.dp),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TagChip(tag: Tag, localizer: Localizer) {
    val text = localizer.text(tag.title)
    val icon = tagIcon(tag.icon)
    if (text.isBlank() && icon == null) return
    val difficulty = Difficulty.of(listOf(tag.title), localizer)
    Surface(
        color = difficulty?.color?.copy(alpha = 0.16f) ?: MaterialTheme.colorScheme.surfaceVariant,
        contentColor = difficulty?.color ?: MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            icon?.let {
                Icon(it, contentDescription = null, modifier = Modifier.size(14.dp))
                if (text.isNotBlank()) Spacer(Modifier.width(4.dp))
            }
            if (text.isNotBlank()) {
                Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = if (difficulty != null) FontWeight.SemiBold else null)
            }
        }
    }
}

private fun tagIcon(name: String?): ImageVector? = when (name) {
    "heartHollow", "heart" -> Icons.Outlined.FavoriteBorder
    "comment" -> Icons.Outlined.ChatBubbleOutline
    "trophy" -> Icons.Outlined.EmojiEvents
    else -> null
}
