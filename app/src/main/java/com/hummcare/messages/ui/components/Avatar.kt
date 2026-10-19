package com.hummcare.messages.ui.components

import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hummcare.messages.data.model.Recipient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

private val AvatarColors = listOf(
    Color(0xFF4F7FE0), Color(0xFF3E9E6E), Color(0xFFD9824A), Color(0xFFC85A72),
    Color(0xFF8B63CC), Color(0xFF2F95A3), Color(0xFFB08A2E),
)

@Composable
fun Avatar(recipients: List<Recipient>, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val single = recipients.singleOrNull()
    val key = recipients.joinToString { it.address }
    val color = AvatarColors[Math.floorMod(key.hashCode(), AvatarColors.size)]
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        val initial = single?.name?.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
        when {
            recipients.size > 1 -> Icon(Icons.Filled.Group, null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
            initial != null -> Text(initial.toString(), color = Color.White, fontSize = (size.value * 0.42f).sp)
            else -> Icon(Icons.Filled.Person, null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
        }
        single?.photoUri?.let { photo ->
            ContentUriImage(photo, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, maxDimension = 256)
        }
    }
}

/** Decodes an image from a content URI off the main thread, downsampled to [maxDimension]. */
@Composable
fun ContentUriImage(
    uri: Uri,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    maxDimension: Int = 1280,
    contentDescription: String? = null,
) {
    val resolver = LocalContext.current.contentResolver
    val bitmap by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                    val largest = max(info.size.width, info.size.height)
                    if (largest > maxDimension) {
                        val scale = maxDimension.toFloat() / largest
                        decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                    }
                }.asImageBitmap()
            }.getOrNull()
        }
    }
    bitmap?.let { Image(it, contentDescription, modifier, contentScale = contentScale) }
}
