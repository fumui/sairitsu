package my.fumui.sairitsu.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A light accent color picked from the cover at [url] (resolved through [LocalUrlResolver]), or null while loading or for grey covers. */
@Composable
fun rememberCoverAccent(url: String?): Color? {
    val model = LocalUrlResolver.current(url)
    val context = LocalContext.current
    var accent by remember(model) { mutableStateOf<Color?>(null) }
    LaunchedEffect(model) {
        if (model == null) return@LaunchedEffect
        val request = ImageRequest.Builder(context).data(model).size(48).allowHardware(false).build()
        val result = context.imageLoader.execute(request) as? SuccessResult ?: return@LaunchedEffect
        accent = withContext(Dispatchers.Default) { accentOf(result.image.toBitmap()) }
    }
    return accent
}

/**
 * The cover's dominant hue, weighted towards saturated, bright pixels, as a color light enough for
 * buttons on the dark theme. Null when the cover is mostly grey.
 */
private fun accentOf(bitmap: Bitmap): Color? {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    val hsv = FloatArray(3)
    var x = 0.0
    var y = 0.0
    var weight = 0.0
    var saturation = 0.0
    for (p in pixels) {
        android.graphics.Color.colorToHSV(p, hsv)
        val wgt = (hsv[1] * hsv[1] * hsv[2]).toDouble()
        val rad = Math.toRadians(hsv[0].toDouble())
        x += cos(rad) * wgt
        y += sin(rad) * wgt
        weight += wgt
        saturation += hsv[1] * wgt
    }
    if (weight < pixels.size * 0.02) return null
    val strength = sqrt(x * x + y * y) / weight
    if (strength < 0.25) return null
    val hue = ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
    val sat = (saturation / weight).toFloat().coerceIn(0.45f, 0.85f)
    return Color.hsl(hue, sat, 0.74f)
}
