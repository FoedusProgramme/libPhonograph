package uk.akane.libphonograph.items

import androidx.media3.common.MediaItem
import uk.akane.libphonograph.fastFilterNotNull
import java.io.File

open class Playlist protected constructor(
    override val id: Long?,
    override val title: String?,
    val path: File?,
    val dateAdded: Long?,
    val dateModified: Long?,
    val hasGaps: Boolean,
) : Item {
    private var _songList: List<MediaItem>? = null
    constructor(id: Long?, title: String?, path: File?, dateAdded: Long?, dateModified: Long?,
                hasGaps: Boolean, songList: List<MediaItem>) : this(id, title, path, dateAdded, dateModified, hasGaps) {
        _songList = songList
    }
    override val songList: List<MediaItem>
        get() = _songList ?: throw IllegalStateException("code bug: Playlist subclass used " +
                "protected constructor but did not override songList")

    override fun hashCode(): Int {
        var result = id?.hashCode() ?: 0
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + songList.hashCode()
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Playlist

        if (id != other.id) return false
        if (title != other.title) return false
        if (songList != other.songList) return false

        return true
    }
}

internal data class RawPlaylist(
    val id: Long,
    val title: String?,
    val path: File?,
    val dateAdded: Long?,
    val dateModified: Long?,
    val idList: List<Long?>,
    val pathList: List<File?>?,
) {
    // idMap may be null if and only if all playlists are empty
    fun toPlaylist(idMap: Map<Long, MediaItem>?, pathMap: Map<String, MediaItem>?): Playlist {
        val tmp = ArrayList<MediaItem?>(idList.size)
        pathList?.forEach { value ->
            tmp.add(value?.let { pathMap!![value.absolutePath] })
        }
	    idList.forEachIndexed { i, value ->
            // if we have an id and it's not in the map, something's weird. but it's not a crash-worthy offense
            if (tmp[i] == null && value != null && idMap!!.containsKey(value)) {
                tmp[i] = idMap[value]!!
            }
        }
        val result = if (tmp.contains(null)) tmp.fastFilterNotNull() else
            @Suppress("UNCHECKED_CAST") (tmp as ArrayList<MediaItem>)
        return Playlist(id, title, path, dateAdded, dateModified, result.size != tmp.size, result)
    }
}