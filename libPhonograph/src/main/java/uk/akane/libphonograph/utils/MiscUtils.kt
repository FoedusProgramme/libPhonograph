package uk.akane.libphonograph.utils

import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import uk.akane.libphonograph.ALLOWED_EXT
import uk.akane.libphonograph.items.Album
import uk.akane.libphonograph.items.FileNode
import uk.akane.libphonograph.items.artistId
import java.io.File

object MiscUtils {
    internal data class FileNodeImpl(
        override val folderName: String
    ) : FileNode {
        override val folderList = hashMapOf<String, FileNode>()
        override val songList = mutableListOf<MediaItem>()
        override var albumId: Long? = null
        fun addSong(item: MediaItem, id: Long?) {
            if (albumId != null && id != albumId) {
                albumId = null
            } else if (albumId == null && songList.isEmpty()) {
                albumId = id
            }
            songList.add(item)
        }
    }

    internal fun handleMediaFolder(path: String, rootNode: FileNode): FileNode {
        val splitPath = path.substring(1, if (path.endsWith('/'))
            path.length - 1 else path.length).split('/')
        var node: FileNode = rootNode
        for (fld in splitPath) {
            var newNode = node.folderList[fld]
            if (newNode == null) {
                newNode = FileNodeImpl(fld)
                (node.folderList as HashMap)[newNode.folderName] = newNode
            }
            node = newNode
        }
        return node
    }

    internal fun handleShallowMediaItem(
        mediaItem: MediaItem,
        albumId: Long?,
        folderName: String,
        shallowFolder: FileNode
    ) {
        var folder = (shallowFolder.folderList as HashMap)[folderName]
        if (folder == null) {
            folder = FileNodeImpl(folderName)
            (shallowFolder.folderList as HashMap)[folder.folderName] = folder
        }
        (folder as FileNodeImpl).addSong(mediaItem, albumId)
    }

    fun findBestCover(songFolder: File): File? {
        var bestScore = 0
        var bestFile: File? = null
        try {
            val files = songFolder.listFiles() ?: return null
            for (file in files) {
                if (file.extension !in ALLOWED_EXT)
                    continue
                var score = 1
                when (file.extension) {
                    "jpg" -> score += 3
                    "png" -> score += 2
                    "jpeg" -> score += 1
                }
                if (file.nameWithoutExtension.contentEquals("albumart", true)) score += 24
                else if (file.nameWithoutExtension.contentEquals("cover", true)) score += 20
                else if (file.nameWithoutExtension.startsWith("albumart", true)) score += 16
                else if (file.nameWithoutExtension.startsWith("cover", true)) score += 12
                else if (file.nameWithoutExtension.contains("albumart", true)) score += 8
                else if (file.nameWithoutExtension.contains("cover", true)) score += 4
                if (bestScore < score) {
                    bestScore = score
                    bestFile = file
                }
            }
        } catch (e: Exception) {
            Log.e("libPhonograph", Log.getStackTraceString(e))
        }
        // allow .jpg or .png files with any name, but only permit more exotic
        // formats if name contains either cover or albumart
        if (bestScore >= 3) {
            return bestFile
        }
        return null
    }

    internal fun findBestAlbumArtist(songs: List<MediaItem>): Pair<String, Long?>? {
        val foundAlbumArtists = songs.groupBy { it.mediaMetadata.albumArtist?.toString() }
            .mapValues { it.value.size }
        if (foundAlbumArtists.size > 2
            || (foundAlbumArtists.size == 2 && !foundAlbumArtists.containsKey(null))) {
            Log.w("libPhonograph", "Odd, album artists: $foundAlbumArtists for one album exceed 1, " +
                    "MediaStore usually doesn't do that")
            return null
        }
        val theAlbumArtist = foundAlbumArtists.keys.find { it != null }
        if (theAlbumArtist != null) {
            // We got at least one album artist tag.
            val countWithNull = foundAlbumArtists[null] ?: 0
            val countWithAlbumArtist = foundAlbumArtists[theAlbumArtist]!!
            if (countWithAlbumArtist < countWithNull) return null
            return Pair(
                theAlbumArtist,
                // If the album artist made some song on the album, using the ID from the song will be
                // more accurate than just using any ID which matches the name. Well, it's a best guess.
                songs.firstOrNull { it.mediaMetadata.artist == theAlbumArtist }?.mediaMetadata?.artistId
            )
        } else {
            // Meh, let's guess based on artist tag.
            val bestMatch = songs.groupBy { it.mediaMetadata.artist?.toString() }.maxByOrNull { it.value.size }
            if (bestMatch?.key == null) return null
            // If less than 60% of songs have said artist, we can't reasonably assume the best match
            // is the actual album artist.
            if ((bestMatch.value.size.toFloat() / songs.size) < 0.6f) return null
            // Let's go with this.
            return Pair(bestMatch.key!!, bestMatch.value.first().mediaMetadata.artistId)
        }
    }

    internal data class AlbumImpl(
        override val id: Long?,
        override val title: String?,
        override var albumArtist: String?,
        override var albumArtistId: Long?,
        override var cover: Uri?,
        override var albumYear: Int?,
        override var albumAddDate: Long?,
        override var albumModifiedDate: Long?,
        override val songList: MutableList<MediaItem>
    ) : Album
}