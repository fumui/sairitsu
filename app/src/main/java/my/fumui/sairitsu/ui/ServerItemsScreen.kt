package my.fumui.sairitsu.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.model.ItemSummary
import my.fumui.sairitsu.gameplay.ItemKind
import my.fumui.sairitsu.gameplay.ItemPreview
import my.fumui.sairitsu.text.Localizer

/** A server's items of one kind: a row each with a one-tap save; tapping a thumbnail shows it full size. */
@Composable
fun ServerItemsScreen(
    vm: ServerItemsViewModel,
    server: ServerAddress,
    localizer: Localizer,
    levels: List<PreviewLevel>,
    onWatch: (PreviewLevel, ItemPreview) -> Unit,
    onBrowse: () -> Unit,
    onBack: () -> Unit,
) {
    val rows by vm.rows.collectAsState()
    val paged by vm.pager.state.collectAsState()
    val previewing by vm.previewing.collectAsState()
    var previewItem by remember { mutableStateOf<ItemSummary?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.errors.collect { snackbar.showSnackbar(it) } }
    val listState = rememberLazyListState()
    LoadMoreEffect(listState, paged, vm.pager::loadMore)
    var viewing by remember { mutableStateOf<Pair<String, String>?>(null) }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, contentWindowInsets = WindowInsets(0)) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).windowInsetsPadding(WindowInsets.displayCutout)) {
            ScreenTopRow(vm.kind.plural.replaceFirstChar { it.uppercase() }, onBack)
            HorizontalDivider()
            Box(Modifier.fillMaxSize()) {
                if (rows.isEmpty()) {
                    EmptyPagerState(paged, "This server has no ${vm.kind.plural}", vm.pager::retry, Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                        items(rows, key = { "item:" + it.first.name }) { (item, state) ->
                            ItemRow(
                                item, state, item.name in previewing, vm, server, localizer,
                                onSave = { vm.save(item) },
                                onPreview = { previewItem = item },
                                onView = { url, title -> viewing = url to title },
                            )
                        }
                        item(key = "footer") { PagerFooter(paged, vm.pager::retry) }
                    }
                }
            }
        }
    }
    viewing?.let { (url, title) -> ImageViewer(url, title, onClose = { viewing = null }) }
    previewItem?.let { item ->
        PreviewLevelDialog(
            itemTitle = localizer.text(item.title),
            levels = levels,
            localizer = localizer,
            onPreview = { level ->
                previewItem = null
                vm.preview(item) { preview -> onWatch(level, preview) }
            },
            onBrowse = {
                previewItem = null
                onBrowse()
            },
            onCancel = { previewItem = null },
        )
    }
}

@Composable
private fun ItemRow(
    item: ItemSummary,
    state: ItemRowState,
    previewing: Boolean,
    vm: ServerItemsViewModel,
    server: ServerAddress,
    localizer: Localizer,
    onSave: () -> Unit,
    onPreview: () -> Unit,
    onView: (url: String, title: String) -> Unit,
) {
    val title = localizer.text(item.title)
    // Backgrounds show their full image (the thumbnail when they have none); other kinds their thumbnail.
    val full = server.resolve((if (vm.kind == ItemKind.Background) item.image ?: item.thumbnail else item.thumbnail)?.url)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(
            server.resolve(item.thumbnail?.url),
            64,
            modifier = if (full != null) Modifier.clip(RoundedCornerShape(8.dp)).clickable { onView(full, title) } else Modifier,
            title = title,
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val byline = listOf(localizer.text(item.subtitle), localizer.text(item.author)).filter { it.isNotBlank() }.joinToString(" · ")
            if (byline.isNotEmpty()) {
                Text(
                    byline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (previewing) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        } else {
            IconButton(onClick = onPreview) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = "Preview $title", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when (state) {
            ItemRowState.NotSaved -> IconButton(onClick = onSave) {
                Icon(Icons.Outlined.Download, contentDescription = "Save $title", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ItemRowState.Saving -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            ItemRowState.Saved -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = "Saved", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** [url] full screen, [title] under it; a tap or back closes. */
@Composable
private fun ImageViewer(url: String, title: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().clickable(onClick = onClose).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AsyncImage(
                model = LocalUrlResolver.current(url),
                contentDescription = title,
                contentScale = ContentScale.Fit,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            Text(
                title,
                Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The back arrow and [title] at the top of a server sub-screen. */
@Composable
internal fun ScreenTopRow(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
    }
}

/** Asks for the next page when the end of [list] nears; re-checks after every page added. */
@Composable
internal fun LoadMoreEffect(list: LazyListState, paged: PagedItems, onLoadMore: () -> Unit) {
    val current by rememberUpdatedState(paged)
    val loadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(list) {
        snapshotFlow {
            val visible = list.layoutInfo.visibleItemsInfo
            val p = current
            // The footer is an extra row after the items, so an index at `size` means the end is on screen.
            if (shouldLoadMore(visible.lastOrNull()?.index ?: -1, visible.size, p.items.size, p.loading)) p.items.size else null
        }.collect { if (it != null) loadMore() }
    }
}

/** The state of a list with no rows yet, kept outside the LazyColumn so a footer item never anchors the scroll position. */
@Composable
internal fun EmptyPagerState(paged: PagedItems, emptyText: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    when {
        paged.error != null -> ErrorMessage(paged.error, onRetry, modifier)
        paged.end -> Text(emptyText, modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> CircularProgressIndicator(modifier)
    }
}

@Composable
internal fun PagerFooter(paged: PagedItems, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
        when {
            paged.loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            paged.error != null -> ErrorMessage(paged.error, onRetry)
        }
    }
}
