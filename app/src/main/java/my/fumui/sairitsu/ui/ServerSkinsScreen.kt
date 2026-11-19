package my.fumui.sairitsu.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.model.ItemSummary
import my.fumui.sairitsu.text.Localizer

/** A server's skins: a row each with a one-tap save. */
@Composable
fun ServerSkinsScreen(vm: ServerSkinsViewModel, server: ServerAddress, localizer: Localizer, onBack: () -> Unit) {
    val rows by vm.rows.collectAsState()
    val paged by vm.pager.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.errors.collect { snackbar.showSnackbar(it) } }
    val listState = rememberLazyListState()
    LoadMoreEffect(listState, paged, vm.pager::loadMore)

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, contentWindowInsets = WindowInsets(0)) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).windowInsetsPadding(WindowInsets.displayCutout)) {
            ScreenTopRow("Skins", onBack)
            HorizontalDivider()
            Box(Modifier.fillMaxSize()) {
                if (rows.isEmpty() && paged.end) {
                    Text(
                        "This server has no skins",
                        Modifier.align(Alignment.Center).padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                        items(rows, key = { "item:" + it.first.name }) { (item, state) ->
                            SkinRow(item, state, server, localizer, onSave = { vm.save(item) })
                        }
                        item(key = "footer") { PagerFooter(paged, vm.pager::retry) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkinRow(item: ItemSummary, state: SkinRowState, server: ServerAddress, localizer: Localizer, onSave: () -> Unit) {
    val title = localizer.text(item.title)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(server.resolve(item.thumbnail?.url), 64, title = title)
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
        when (state) {
            SkinRowState.NotSaved -> IconButton(onClick = onSave) {
                Icon(Icons.Outlined.Download, contentDescription = "Save $title", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SkinRowState.Saving -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            SkinRowState.Saved -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = "Saved", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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

@Composable
internal fun PagerFooter(paged: PagedItems, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
        when {
            paged.loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            paged.error != null -> ErrorMessage(paged.error, onRetry)
        }
    }
}
