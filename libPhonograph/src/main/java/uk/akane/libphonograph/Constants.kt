package uk.akane.libphonograph

import androidx.core.net.toUri

internal val ALLOWED_EXT = listOf("jpg", "png", "jpeg", "bmp", "tiff", "tif", "webp")

object Constants {
    val baseAlbumCoverUri = "content://media/external/audio/albumart".toUri()
}