package my.fumui.sairitsu.gameplay

import android.os.Vibrator
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

/** The overlay above the gameplay SurfaceView: opaque while loading or failed, see-through while the level runs. */
@Composable
fun GameplayScreen(vm: GameplayViewModel, pictures: ItemPictures, onBack: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }
    Box(Modifier.fillMaxSize()) {
        GameplayContent(vm, pictures, onBack)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomStart).windowInsetsPadding(WindowInsets.safeDrawing))
    }
}

@Composable
private fun GameplayContent(vm: GameplayViewModel, pictures: ItemPictures, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    when (val s = state) {
        // The level draws on the SurfaceView underneath; the built-in bottom bar is drawn here, above it.
        is GameplayUiState.Ready -> if (vm.isPlay) PlayLayer(vm) else WatchOverlay(vm)
        // The run is over: the last frame stays behind the result screen.
        is GameplayUiState.Ended -> {
            val info by vm.resultInfo.collectAsStateWithLifecycle()
            val level by vm.levelInfo.collectAsStateWithLifecycle()
            val replaySave by vm.replaySave.collectAsStateWithLifecycle()
            val timing by vm.timing.collectAsStateWithLifecycle()
            val offsetApply by vm.offsetApply.collectAsStateWithLifecycle()
            val offsetDialog by vm.offsetDialog.collectAsStateWithLifecycle()
            ResultScreen(
                s.result, s.partial, info, level,
                timing?.let { TimingUi(it, vm.canEditOffset, offsetApply, vm::openOffsetDialog) },
                replaySave,
                onSaveReplay = vm::saveReplay, onRetry = vm::retryFromResult, onBack = onBack,
            )
            offsetDialog?.let { ApplyOffsetDialog(it, onApply = vm::applyOffset, onDismiss = vm::dismissOffsetDialog) }
        }
        // The level stays on its last frame behind the message.
        is GameplayUiState.Stopped -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
                Box(Modifier.padding(24.dp)) { Stopped(s.message, onBack) }
            }
        }
        is GameplayUiState.Options -> OptionsScreen(
            s, pictures, onOption = vm::setOption, onSettings = vm::setSettings, onInputOffset = vm::setInputOffset,
            onLevelItem = vm::setLevelItem, onEngineItem = vm::setEngineItem, onBack = onBack,
        )
        else -> Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                when (s) {
                    is GameplayUiState.Loading -> Loading(s.progress)
                    is GameplayUiState.Failed -> Failed(s.message, onRetry = vm::retry, onBack = onBack)
                }
            }
        }
    }
}

/**
 * Play (the bar is Watch-only): polls for a fault, the countdown and the run's end each UI frame, and draws the
 * built-in HUD the engine shows ([UiCanvas]), the pause button, the pause menu and the countdown's digits over the
 * stage. The system Back button opens the menu too.
 */
@Composable
private fun PlayLayer(vm: GameplayViewModel) {
    var menuBox by remember { mutableStateOf<FloatArray?>(null) }
    val vibrator = LocalContext.current.getSystemService(Vibrator::class.java)
    LaunchedEffect(vm) {
        while (true) {
            withFrameNanos { }
            vm.pollFault()
            val levels = vm.pollPlay()
            // One vibration per UI frame, for the strongest level queued since the last one.
            if (levels.isNotEmpty() && vm.hapticsOn && vibrator != null && vibrator.hasVibrator()) {
                Haptics.effect(levels.max())?.let { vibrator.vibrate(it) }
            }
            if (menuBox == null) menuBox = FloatArray(4).takeIf { vm.menuRect(it) }
        }
    }
    val ui by vm.playUi.collectAsStateWithLifecycle()
    // Back: opens the menu; with the menu up it resumes; during the countdown it pauses again.
    BackHandler {
        when (ui) {
            PlayUi.Paused -> vm.resumePlay()
            else -> vm.pause()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        UiCanvas(vm)
        when (val u = ui) {
            PlayUi.Running -> PauseTarget(menuBox, vm::pause)
            PlayUi.Paused -> PauseMenu(onResume = vm::resumePlay, onRetry = vm::retryPlay, onQuit = vm::quit)
            is PlayUi.Countdown -> Text(
                u.digit.toString(),
                Modifier.align(Alignment.Center),
                color = Color.White,
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 160.sp, fontWeight = FontWeight.Bold),
            )
        }
    }
}

/**
 * The pause control: an invisible hit target over what shows the pause button (the engine's own icon, or the HUD's
 * menu widget when the engine shows it, drawn by [UiCanvas]). It sits on the
 * engine's Menu widget box (Runtime UI, in screen units: x in -aspect..aspect, y up in -1..1, `box` = left, bottom,
 * right, top); until the engine has set it, a 48 dp target at the top right.
 */
@Composable
private fun BoxWithConstraintsScope.PauseTarget(box: FloatArray?, onClick: () -> Unit) {
    val half = maxHeight / 2
    val place = if (box == null) {
        Modifier.align(Alignment.TopEnd).padding(16.dp).size(48.dp)
    } else {
        Modifier
            .offset(x = maxWidth / 2 + half * box[0], y = half - half * box[3])
            .size(half * (box[2] - box[0]), half * (box[3] - box[1]))
    }
    Box(
        place
            .semantics { contentDescription = "Pause" }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    )
}

/**
 * Watch: the built-in HUD and the bottom bar over the SurfaceView (see [UiCanvas]). Also polls for a fault that
 * stopped the level.
 */
@Composable
private fun WatchOverlay(vm: GameplayViewModel) {
    LaunchedEffect(vm) {
        while (true) {
            withFrameNanos { }
            vm.pollFault()
        }
    }
    val note by vm.note.collectAsStateWithLifecycle()
    val slop = with(LocalDensity.current) { WATCH_HIT_SLOP.toPx() }
    Box(Modifier.fillMaxSize()) {
        UiCanvas(vm)
        // The bar's hit rects come from the native state read at the press (the same geometry UiCanvas draws), so
        // there is nothing to keep in sync per frame.
        Box(Modifier.fillMaxSize().pointerInput(vm, slop) { watchGestures(vm, slop) })
        note?.let {
            Text(
                it,
                Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private val WATCH_HIT_SLOP = 12.dp

/** The music time under [x] on a track spanning [left]..[right] (any unit) of a [length] s level. */
internal fun trackTime(x: Float, left: Float, right: Float, length: Float): Float =
    if (right <= left) 0f else length * ((x - left) / (right - left)).coerceIn(0f, 1f)

/**
 * Watch's touches: a press on the bar's track scrubs (each move seeks with `scrubbing`, and the gesture always ends
 * with a seek that is not scrubbing, a cancel included); a tap on the pause or repeat control presses it; any other
 * tap shows a hidden bar (a tap while it shows leaves it to fade).
 */
private suspend fun PointerInputScope.watchGestures(vm: GameplayViewModel, slop: Float) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // The window can be resized between gestures, so the mapping comes from the size now.
        val half = size.height / 2f
        val width = size.width
        fun px(x: Float) = width / 2f + x * half
        fun py(y: Float) = half - y * half
        val s = FloatArray(WatchState.FLOATS)
        if (!vm.watchState(s)) return@awaitEachGesture
        val visible = s[WatchState.VISIBLE] != 0f
        fun hit(at: Int, p: androidx.compose.ui.geometry.Offset, minHeight: Float = 0f): Boolean {
            val growY = maxOf(slop, (minHeight - (py(s[at + 1]) - py(s[at + 3]))) / 2f)
            return p.x in (px(s[at]) - slop)..(px(s[at + 2]) + slop) &&
                p.y in (py(s[at + 3]) - growY)..(py(s[at + 1]) + growY)
        }
        if (visible && hit(WatchState.TRACK, down.position, minHeight = slop * 4f)) {
            val left = px(s[WatchState.TRACK])
            val right = px(s[WatchState.TRACK + 2])
            var last = trackTime(down.position.x, left, right, s[WatchState.LENGTH])
            try {
                vm.seek(last, true)
                down.consume()
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    last = trackTime(change.position.x, left, right, s[WatchState.LENGTH])
                    if (!change.pressed) break
                    vm.seek(last, true)
                    change.consume()
                }
            } finally {
                vm.seek(last, false)
            }
            return@awaitEachGesture
        }
        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
        up.consume()
        when {
            !visible -> vm.showBar()
            hit(WatchState.PAUSE_RECT, down.position) -> vm.toggleWatchPause()
            hit(WatchState.REPEAT_RECT, down.position) -> vm.setRepeat(s[WatchState.REPEAT] == 0f)
        }
    }
}

@Composable
private fun Stopped(message: String, onBack: () -> Unit) {
    Column(Modifier.widthIn(max = 560.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer8()
        Button(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun Loading(progress: LoadProgress?) {
    Column(Modifier.widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Loading level", style = MaterialTheme.typography.titleMedium)
        Spacer8()
        val fraction = progress?.fraction()
        if (fraction == null) {
            LinearProgressIndicator(Modifier.width(320.dp))
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.width(320.dp))
        }
        progress?.label()?.let {
            Spacer8()
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Failed(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.widthIn(max = 560.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Couldn't load this level", style = MaterialTheme.typography.titleMedium)
        Spacer8()
        Text(
            message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer8()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRetry) { Text("Retry") }
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
    }
}

@Composable
private fun Spacer8() = Spacer(Modifier.height(8.dp))

/** Overall fraction done, counting the current download's bytes; null when the total is unknown. */
private fun LoadProgress.fraction(): Float? {
    if (total <= 0) return null
    val partial = totalBytes?.takeIf { it > 0 }?.let { (bytes.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
    return ((done + partial) / total).coerceIn(0f, 1f)
}

private fun LoadProgress.label(): String? {
    val name = kind?.wireName ?: return null
    if (bytes <= 0) return "$done of $total · $name"
    return "$done of $total · $name · ${mb(bytes)}${totalBytes?.let { " of ${mb(it)}" } ?: ""}"
}

private fun mb(bytes: Long) = String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
