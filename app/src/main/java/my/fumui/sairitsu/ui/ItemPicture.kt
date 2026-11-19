package my.fumui.sairitsu.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import my.fumui.sairitsu.gameplay.ItemKind
import my.fumui.sairitsu.gameplay.ItemPictures
import my.fumui.sairitsu.gameplay.SavedItem

/** The kind's icon, as on the server hub's buttons. */
fun ItemKind.icon(): ImageVector = when (this) {
    ItemKind.Skin -> Icons.Outlined.Palette
    ItemKind.Background -> Icons.Outlined.Wallpaper
    ItemKind.Sfx -> Icons.Outlined.GraphicEq
    ItemKind.Particles -> Icons.Outlined.AutoAwesome
}

/**
 * [item]'s picture filling [modifier]'s size: a background cropped to fill, sprite strips fitted. The [kind]'s icon
 * shows while it loads, when [item] is null, and when there is no picture.
 */
@Composable
fun ItemPicture(item: SavedItem?, kind: ItemKind, pictures: ItemPictures, modifier: Modifier = Modifier) {
    val picture by produceState<ImageBitmap?>(null, item) { value = item?.let { pictures.picture(it) } }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val image = picture
        if (image != null) {
            val crop = kind == ItemKind.Background
            Image(
                image,
                contentDescription = null,
                modifier = if (crop) Modifier.fillMaxSize() else Modifier.fillMaxSize().padding(4.dp),
                contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
            )
        } else {
            Icon(kind.icon(), contentDescription = null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
