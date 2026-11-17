package my.fumui.sairitsu.gameplay

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

// Digits and capitals are this much of the em in the app's font (Roboto; render::kCapHeightEm).
private const val CAP_HEIGHT_EM = 0.711f

/**
 * The built-in HUD widgets and, in Watch, the control bar over the SurfaceView, redrawn every frame. The shapes,
 * positions and texts come from the native `build_hud` / `build_overlay` (render/ui.cpp, the one source); here they
 * are only drawn, the text in the app's own font (the platform default, Roboto, as the rest of the app).
 */
@Composable
fun UiCanvas(vm: GameplayViewModel, modifier: Modifier = Modifier) {
    val buffer = remember { FloatArray(NativeOverlay.FLOATS) }
    val regular = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT } }
    val bold = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD } }
    val outline = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
        }
    }
    val showFps by vm.showFps.collectAsState()
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(vm) {
        while (true) withFrameNanos { frame = it }
    }
    Canvas(modifier.fillMaxSize()) {
        frame // redraw every frame
        if (showFps) drawFps(vm.renderFps(), bold, outline)
        val joined = vm.overlay(buffer) ?: return@Canvas
        val half = size.height / 2f
        fun px(x: Float) = size.width / 2f + x * half
        fun py(y: Float) = half - y * half
        val rects = buffer[NativeOverlay.RECTS].toInt()
        val texts = buffer[NativeOverlay.TEXTS].toInt()
        val icons = buffer[NativeOverlay.ICONS].toInt()
        var o = NativeOverlay.HEADER
        repeat(rects) {
            val path = Path().apply {
                moveTo(px(buffer[o]), py(buffer[o + 4]))
                for (k in 1..3) lineTo(px(buffer[o + k]), py(buffer[o + 4 + k]))
                close()
            }
            drawPath(path, Color(buffer[o + 8], buffer[o + 9], buffer[o + 10], buffer[o + 11]))
            o += NativeOverlay.PER_RECT
        }
        val textAt = o
        o += texts * NativeOverlay.PER_TEXT
        repeat(icons) {
            val x = FloatArray(4) { px(buffer[o + 1 + it]) }
            val y = FloatArray(4) { py(buffer[o + 5 + it]) }
            val color = Color(buffer[o + 9], buffer[o + 10], buffer[o + 11], buffer[o + 12])
            drawIcon(buffer[o].toInt(), x, y, color)
            o += NativeOverlay.PER_ICON
        }
        val lines = joined.split('\n')
        drawIntoCanvas { canvas ->
            for (i in 0 until texts) {
                val t = textAt + i * NativeOverlay.PER_TEXT
                val text = lines.getOrNull(i) ?: break
                val paint = if (buffer[t + 8] != 0f) bold else regular
                paint.textSize = buffer[t + 2] * half
                val x = px(buffer[t]) - buffer[t + 3] * paint.measureText(text)
                val baseline = py(buffer[t + 1]) + paint.textSize * CAP_HEIGHT_EM / 2f
                val alpha = buffer[t + 7]
                if (buffer[t + 9] != 0f) {
                    outline.typeface = paint.typeface
                    outline.textSize = paint.textSize
                    outline.strokeWidth = paint.textSize * 0.14f
                    outline.color = Color(0f, 0f, 0f, 0.75f * alpha).toArgb()
                    canvas.nativeCanvas.drawText(text, x, baseline, outline)
                }
                paint.color = Color(buffer[t + 4], buffer[t + 5], buffer[t + 6], alpha).toArgb()
                canvas.nativeCanvas.drawText(text, x, baseline, paint)
            }
        }
    }
}

/**
 * "120 FPS" at the top left, below where engines put their top widgets (the score box ends near 10 % of the height),
 * outlined like the HUD's texts; "– FPS" until the first half second is counted.
 */
private fun DrawScope.drawFps(fps: Float, paint: Paint, outline: Paint) {
    val text = if (fps > 0f) "${fps.roundToInt()} FPS" else "– FPS"
    paint.textSize = size.height * 0.03f
    outline.typeface = paint.typeface
    outline.textSize = paint.textSize
    outline.strokeWidth = paint.textSize * 0.14f
    outline.color = Color(0f, 0f, 0f, 0.75f).toArgb()
    paint.color = Color.White.toArgb()
    val x = size.height * 0.025f
    val baseline = size.height * 0.15f
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawText(text, x, baseline, outline)
        canvas.nativeCanvas.drawText(text, x, baseline, paint)
    }
}

// render::UiIconKind, in enum order.
private object IconKind {
    const val FLAME = 0
    const val TARGET = 1
    const val HEART = 2
    const val CLOCK = 3
    const val STAR = 4
    const val CROSS = 5
    const val PLUS = 6
    const val MINUS = 7
    const val ARROW_UP = 8
    const val ARROW_DOWN = 9
    const val ARROW_LEFT = 10
    const val ARROW_RIGHT = 11
    const val TRIANGLE_UP = 12
    const val TRIANGLE_DOWN = 13
    const val TRIANGLE_LEFT = 14
    const val TRIANGLE_RIGHT = 15
}

/**
 * One glyph in the box with corners [x], [y] (bl, tl, tr, br, in px). Glyphs are drawn in the box's own unit square
 * (u right, v up), so they follow its rotation. Simple original glyphs.
 */
private fun DrawScope.drawIcon(kind: Int, x: FloatArray, y: FloatArray, color: Color) {
    // The point at (u, v) of the unit square.
    fun p(u: Float, v: Float) = Offset(
        x[0] + u * (x[3] - x[0]) + v * (x[1] - x[0]),
        y[0] + u * (y[3] - y[0]) + v * (y[1] - y[0]),
    )
    val side = hypot(x[1] - x[0], y[1] - y[0])
    val dark = Color(0f, 0f, 0f, color.alpha)
    fun stroke(w: Float) = Stroke(width = w * side, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun poly(vararg uv: Float, closed: Boolean) = Path().apply {
        moveTo(p(uv[0], uv[1]).x, p(uv[0], uv[1]).y)
        for (i in 2 until uv.size step 2) p(uv[i], uv[i + 1]).let { lineTo(it.x, it.y) }
        if (closed) close()
    }
    fun curve(path: Path, vararg uv: Float) {
        val a = p(uv[0], uv[1])
        val b = p(uv[2], uv[3])
        val c = p(uv[4], uv[5])
        path.cubicTo(a.x, a.y, b.x, b.y, c.x, c.y)
    }
    fun circle(cu: Float, cv: Float, r: Float, segments: Int = 32) = Path().apply {
        for (i in 0..segments) {
            val t = (i.toFloat() / segments) * 2f * Math.PI.toFloat()
            val q = p(cu + r * cos(t), cv + r * sin(t))
            if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y)
        }
        close()
    }
    // An arrow or triangle pointing up, turned for the other directions.
    fun turned(kind: Int, base: Int, u: Float, v: Float): Pair<Float, Float> = when (kind - base) {
        0 -> u to v // up
        1 -> u to 1f - v // down
        2 -> 1f - v to u // left
        else -> v to u // right
    }
    when (kind) {
        IconKind.FLAME -> {
            val path = Path().apply {
                p(0.5f, 1f).let { moveTo(it.x, it.y) }
                curve(this, 0.62f, 0.78f, 0.92f, 0.62f, 0.86f, 0.32f)
                curve(this, 0.8f, 0.08f, 0.64f, 0f, 0.5f, 0f)
                curve(this, 0.36f, 0f, 0.2f, 0.08f, 0.14f, 0.32f)
                curve(this, 0.1f, 0.52f, 0.28f, 0.62f, 0.34f, 0.8f)
                curve(this, 0.42f, 0.7f, 0.46f, 0.82f, 0.5f, 1f)
                close()
            }
            drawPath(path, color)
            val inner = Path().apply {
                p(0.5f, 0.52f).let { moveTo(it.x, it.y) }
                curve(this, 0.6f, 0.38f, 0.66f, 0.28f, 0.62f, 0.18f)
                curve(this, 0.58f, 0.1f, 0.42f, 0.1f, 0.38f, 0.18f)
                curve(this, 0.34f, 0.28f, 0.4f, 0.38f, 0.5f, 0.52f)
                close()
            }
            drawPath(inner, dark)
        }
        IconKind.HEART -> {
            val path = Path().apply {
                p(0.5f, 0.08f).let { moveTo(it.x, it.y) }
                curve(this, 0.1f, 0.38f, 0f, 0.62f, 0.12f, 0.82f)
                curve(this, 0.26f, 1f, 0.44f, 0.94f, 0.5f, 0.76f)
                curve(this, 0.56f, 0.94f, 0.74f, 1f, 0.88f, 0.82f)
                curve(this, 1f, 0.62f, 0.9f, 0.38f, 0.5f, 0.08f)
                close()
            }
            drawPath(path, color)
            drawPath(poly(0.12f, 0.52f, 0.34f, 0.52f, 0.44f, 0.72f, 0.56f, 0.32f, 0.66f, 0.52f, 0.88f, 0.52f, closed = false), dark, style = stroke(0.08f))
        }
        IconKind.TARGET -> {
            drawPath(circle(0.5f, 0.5f, 0.42f), color, style = stroke(0.1f))
            drawPath(circle(0.5f, 0.5f, 0.22f), color, style = stroke(0.1f))
            drawPath(circle(0.5f, 0.5f, 0.06f), color)
        }
        IconKind.CLOCK -> {
            drawPath(circle(0.5f, 0.5f, 0.42f), color, style = stroke(0.1f))
            drawPath(poly(0.5f, 0.78f, 0.5f, 0.5f, 0.7f, 0.4f, closed = false), color, style = stroke(0.1f))
        }
        IconKind.STAR -> {
            val pts = FloatArray(20)
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) 0.48f else 0.2f
                val t = Math.PI.toFloat() / 2f + i * Math.PI.toFloat() / 5f
                pts[2 * i] = 0.5f + r * cos(t)
                pts[2 * i + 1] = 0.48f + r * sin(t)
            }
            drawPath(poly(*pts, closed = true), color)
        }
        IconKind.CROSS -> {
            drawPath(poly(0.18f, 0.18f, 0.82f, 0.82f, closed = false), color, style = stroke(0.16f))
            drawPath(poly(0.18f, 0.82f, 0.82f, 0.18f, closed = false), color, style = stroke(0.16f))
        }
        IconKind.PLUS -> {
            drawPath(poly(0.15f, 0.5f, 0.85f, 0.5f, closed = false), color, style = stroke(0.18f))
            drawPath(poly(0.5f, 0.15f, 0.5f, 0.85f, closed = false), color, style = stroke(0.18f))
        }
        IconKind.MINUS -> drawPath(poly(0.15f, 0.5f, 0.85f, 0.5f, closed = false), color, style = stroke(0.18f))
        in IconKind.ARROW_UP..IconKind.ARROW_RIGHT -> {
            fun q(u: Float, v: Float) = turned(kind, IconKind.ARROW_UP, u, v)
            val (a, b) = q(0.5f, 0.1f)
            val (c, d) = q(0.5f, 0.88f)
            drawPath(poly(a, b, c, d, closed = false), color, style = stroke(0.16f))
            val (e, f) = q(0.18f, 0.56f)
            val (g, h) = q(0.82f, 0.56f)
            drawPath(poly(e, f, c, d, g, h, closed = false), color, style = stroke(0.16f))
        }
        in IconKind.TRIANGLE_UP..IconKind.TRIANGLE_RIGHT -> {
            fun q(u: Float, v: Float) = turned(kind, IconKind.TRIANGLE_UP, u, v)
            val (a, b) = q(0.5f, 0.9f)
            val (c, d) = q(0.08f, 0.14f)
            val (e, f) = q(0.92f, 0.14f)
            drawPath(poly(a, b, c, d, e, f, closed = true), color)
        }
    }
}
