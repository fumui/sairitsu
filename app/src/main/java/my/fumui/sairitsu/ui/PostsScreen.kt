package my.fumui.sairitsu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.model.ItemSummary
import my.fumui.sairitsu.text.Localizer
import java.text.DateFormat
import java.util.Date

private const val LIST_WEIGHT = 0.38f
private const val READER_WEIGHT = 0.62f

/** A server's news posts: the list on the left, the selected post's text on the right. */
@Composable
fun PostsScreen(vm: PostsViewModel, server: ServerAddress, localizer: Localizer, onBack: () -> Unit) {
    val posts by vm.posts.collectAsState()
    val paged by vm.pager.state.collectAsState()
    val selected by vm.selected.collectAsState()
    val details by vm.details.collectAsState()
    val listState = rememberLazyListState()
    LoadMoreEffect(listState, paged, vm.pager::loadMore)

    Scaffold(contentWindowInsets = WindowInsets(0)) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).windowInsetsPadding(WindowInsets.displayCutout)) {
            ScreenTopRow("Posts", onBack)
            HorizontalDivider()
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(LIST_WEIGHT).fillMaxSize()) {
                    if (posts.isEmpty() && paged.end) {
                        Text(
                            "This server has no posts",
                            Modifier.align(Alignment.Center).padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(posts, key = { "item:" + it.name }) { post ->
                                PostRow(post, post.name == selected, server, localizer) { vm.select(post.name) }
                            }
                            item(key = "footer") { PagerFooter(paged, vm.pager::retry) }
                        }
                    }
                }
                VerticalDivider()
                Box(Modifier.weight(READER_WEIGHT).fillMaxSize()) {
                    when (val d = details) {
                        Loadable.Idle -> Text(
                            "Select a post",
                            Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Loadable.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        is Loadable.Error -> ErrorMessage(d.message, vm::retryDetails, Modifier.align(Alignment.Center))
                        is Loadable.Ready -> PostReader(d.value, server, localizer)
                    }
                }
            }
        }
    }
}

@Composable
private fun PostRow(post: ItemSummary, selected: Boolean, server: ServerAddress, localizer: Localizer, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    Row(
        Modifier.fillMaxWidth().background(background).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val title = localizer.text(post.title)
        if (post.thumbnail?.url != null) Cover(server.resolve(post.thumbnail.url), 56, title = title)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val line = byline(post, localizer)
            if (line.isNotEmpty()) {
                Text(
                    line,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PostReader(post: PostView, server: ServerAddress, localizer: Localizer) {
    val summary = post.summary
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val image = server.resolve(summary.thumbnail?.url)
        if (image != null) {
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).clip(RoundedCornerShape(12.dp)),
            )
        }
        Text(localizer.text(summary.title), style = MaterialTheme.typography.headlineSmall)
        val line = byline(summary, localizer)
        if (line.isNotEmpty()) {
            Text(line, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        post.description?.let {
            SelectionContainer { Text(localizer.text(it), style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

/** `author · date`, with a blank author or an unset time left out. */
private fun byline(post: ItemSummary, localizer: Localizer): String =
    listOf(
        localizer.text(post.author),
        if (post.time == 0L) "" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(post.time)),
    ).filter { it.isNotBlank() }.joinToString(" · ")
