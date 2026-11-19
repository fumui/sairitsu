package my.fumui.sairitsu.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import my.fumui.sairitsu.data.CatalogSummary
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.SyncStatus
import my.fumui.sairitsu.data.model.ServerInfo
import my.fumui.sairitsu.text.Localizer

/**
 * A server's hub: its title, description and catalog line on the left, a button per section it offers on the right,
 * over the server's banner. [savedTitle] (the user's name for it) wins over the server's own title.
 */
@Composable
fun ServerHubScreen(
    server: ServerAddress,
    savedTitle: String?,
    info: Loadable<ServerInfo>,
    catalog: CatalogSummary?,
    localizer: Localizer,
    message: String?,
    onMessageShown: () -> Unit,
    onSection: (HubSection) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val ready = (info as? Loadable.Ready)?.value
    val banner = server.resolve(ready?.banner?.url)
    val coverAccent = rememberCoverAccent(banner)
    val accent by animateColorAsState(coverAccent ?: MaterialTheme.colorScheme.primary, tween(600), label = "accent")
    val scheme = MaterialTheme.colorScheme.copy(primary = accent, onPrimary = OnBadge)
    val title = savedTitle ?: ready?.title?.let(localizer::text) ?: server.linkAddress
    val buttons = remember(ready, localizer) { hubButtons(ready, localizer) }
    val snackbar = remember { SnackbarHostState() }

    // The effect is cancelled once the message is cleared, so it clears only after the snackbar is gone.
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            onMessageShown()
        }
    }

    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                if (banner != null) DriftingCover(banner)
                val surface = MaterialTheme.colorScheme.surface
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(listOf(surface.copy(alpha = 0.55f), surface.copy(alpha = 0.8f), surface.copy(alpha = 0.92f))),
                    ),
                )
                Row(
                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout).padding(horizontal = 48.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                        Heading(title, ready?.description?.let(localizer::text), catalog)
                    }
                    Column(
                        Modifier.width(300.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (info is Loadable.Loading || info is Loadable.Idle) {
                            Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(32.dp))
                            }
                        } else {
                            buttons.forEach { button ->
                                MenuButton(button.label, button.section.icon(), primary = button.section == HubSection.Levels) {
                                    onSection(button.section)
                                }
                            }
                            if (info is Loadable.Error) ErrorMessage(info.message, onRetry)
                        }
                    }
                }
                IconButton(
                    onClick = onBack,
                    Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.displayCutout).padding(start = 8.dp, top = 8.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
private fun Heading(title: String, description: String?, catalog: CatalogSummary?) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Box(Modifier.width(72.dp).height(4.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(14.dp))
            Text(
                description,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        catalogLine(catalog)?.let { line ->
            Spacer(Modifier.height(8.dp))
            Text(
                line,
                style = MaterialTheme.typography.labelLarge,
                color = if (catalog?.status is SyncStatus.Failed) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun HubSection.icon(): ImageVector = when (this) {
    HubSection.Levels -> Icons.Outlined.MusicNote
    HubSection.Posts -> Icons.AutoMirrored.Outlined.Article
    HubSection.Skins -> Icons.Outlined.Palette
    HubSection.Backgrounds -> Icons.Outlined.Wallpaper
    HubSection.Sfx -> Icons.Outlined.GraphicEq
    HubSection.Particles -> Icons.Outlined.AutoAwesome
    HubSection.Playlists -> Icons.AutoMirrored.Outlined.QueueMusic
    HubSection.Configuration -> Icons.Outlined.Tune
}
