package uk.akane.libphonograph.items

import android.net.Uri
import androidx.media3.common.MediaItem

interface Album : Item {
	override val id: Long?
	override val title: String?
	override val songList: List<MediaItem>
	val albumArtist: String?
	val albumArtistId: Long?
	val albumYear: Int? // Last year
	val albumAddDate: Long?
	val albumModifiedDate: Long?
	val cover: Uri?
}