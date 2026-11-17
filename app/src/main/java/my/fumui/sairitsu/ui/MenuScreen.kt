package my.fumui.sairitsu.ui

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

/**
 * The first screen: the app title on the left, Play, Online Servers, Settings and Exit on the right,
 * over a slowly drifting, blurred cover picked from [covers] (the downloaded levels' cover files).
 */
@Composable
fun MenuScreen(
    onPlay: () -> Unit,
    onCollections: () -> Unit,
    onServers: () -> Unit,
    onSettings: () -> Unit,
    onExit: () -> Unit,
    covers: List<String> = emptyList(),
    levelCount: Int = 0,
) {
    val cover = remember(covers.isEmpty()) { covers.randomOrNull() }
    val coverAccent = rememberCoverAccent(cover)
    val accent by animateColorAsState(coverAccent ?: MaterialTheme.colorScheme.primary, tween(600), label = "accent")
    val scheme = MaterialTheme.colorScheme.copy(primary = accent, onPrimary = OnBadge)
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }

    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                if (cover != null) DriftingCover(cover)
                val surface = MaterialTheme.colorScheme.surface
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(listOf(surface.copy(alpha = 0.55f), surface.copy(alpha = 0.8f), surface.copy(alpha = 0.92f))),
                    ),
                )
                Row(
                    // Five buttons fit a 360 dp-tall landscape screen: 5 × 52 + 4 × 10 + 2 × 16 = 332 dp.
                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout).padding(horizontal = 48.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                        Title(levelCount)
                    }
                    Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        MenuButton("Play", Icons.Outlined.PlayArrow, primary = true, onClick = onPlay)
                        MenuButton("Collections", Icons.Outlined.CollectionsBookmark, onClick = onCollections)
                        MenuButton("Online Servers", Icons.Outlined.Language, onClick = onServers)
                        MenuButton("Settings", Icons.Outlined.Settings, onClick = onSettings)
                        MenuButton("Exit", Icons.AutoMirrored.Outlined.ExitToApp, onClick = onExit)
                    }
                }
                if (version != null) {
                    Text(
                        "v$version",
                        Modifier.align(Alignment.BottomStart).windowInsetsPadding(WindowInsets.displayCutout).padding(start = 48.dp, bottom = 20.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Title(levelCount: Int) {
    Column {
        Text(
            "SAIRITSU",
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Black,
            letterSpacing = 8.sp,
        )
        Spacer(Modifier.height(10.dp))
        Box(Modifier.width(72.dp).height(4.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
        if (levelCount > 0) {
            Spacer(Modifier.height(14.dp))
            Text(
                if (levelCount == 1) "1 level downloaded" else "$levelCount levels downloaded",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** [url] filling the screen, blurred, slowly zooming and drifting sideways. */
@Composable
private fun DriftingCover(url: String) {
    val transition = rememberInfiniteTransition(label = "drift")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(30_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "t",
    )
    val blurs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val scale = 1.15f + 0.1f * t
                scaleX = scale
                scaleY = scale
                translationX = (t - 0.5f) * size.width * 0.06f
            }
            .then(if (blurs) Modifier.blur(36.dp) else Modifier)
            .alpha(if (blurs) 0.7f else 0.3f),
    )
}

@Composable
private fun MenuButton(label: String, icon: ImageVector, primary: Boolean = false, onClick: () -> Unit) {
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, contentDescription = null, Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
    }
    val modifier = Modifier.fillMaxWidth().height(52.dp)
    if (primary) Button(onClick = onClick, modifier = modifier, content = content)
    else OutlinedButton(onClick = onClick, modifier = modifier, content = content)
}
