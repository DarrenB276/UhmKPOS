package com.uhmk.pos.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import java.io.File
import kotlin.math.absoluteValue

/**
 * Product image, or a generated initials tile when the item has no photo.
 *
 * The fallback colour is derived from the item name, so an item keeps the same tile every time it
 * is drawn and the grid stays visually scannable even with no photos at all.
 */
@Composable
fun ItemThumbnail(
    name: String,
    imagePath: String?,
    modifier: Modifier = Modifier,
    size: Dp? = null,
    corner: Dp = 12.dp,
) {
    val shape = RoundedCornerShape(corner)
    val base = modifier
        .then(if (size != null) Modifier.size(size) else Modifier)
        .clip(shape)

    if (!imagePath.isNullOrBlank() && File(imagePath).exists()) {
        AsyncImage(
            model = File(imagePath),
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = base,
        )
    } else {
        Box(
            modifier = base.background(tileColor(name)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initialsOf(name),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = if ((size ?: 56.dp) < 48.dp) 14.sp else 20.sp,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

private val palette = listOf(
    Color(0xFFB3261E), Color(0xFF9A3412), Color(0xFF7C3AED), Color(0xFF1D4ED8),
    Color(0xFF0F766E), Color(0xFF15803D), Color(0xFFB45309), Color(0xFFBE185D),
)

fun tileColor(name: String): Color =
    palette[(name.hashCode().absoluteValue) % palette.size]

fun initialsOf(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.first().isLetterOrDigit() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> (words[0].first().toString() + words[1].first()).uppercase()
    }
}
