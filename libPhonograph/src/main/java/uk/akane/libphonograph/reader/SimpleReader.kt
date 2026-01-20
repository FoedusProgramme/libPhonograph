package uk.akane.libphonograph.reader

import android.content.Context
import androidx.media3.common.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import uk.akane.libphonograph.dynamicitem.RecentlyAdded

object SimpleReader {
    fun readFromMediaStore(
        context: Context,
        minSongLengthSeconds: Long = 0,
        blackListSet: Set<String> = setOf(),
        shouldUseEnhancedCoverReading: Boolean? = false, // null means load if permission is granted
        recentlyAddedFilterSecond: Long? = 1_209_600, // null means don't generate recently added
        shouldIncludeExtraFormat: Boolean = true,
        coverStubUri: String? = null
    ): SimpleReaderResult {
        val playlistResult = Reader.fetchPlaylists(context)
        val result = runBlocking {
            withContext(Dispatchers.Default) {
                Reader.readFromMediaStore(
                    context, minSongLengthSeconds, blackListSet,
                    shouldUseEnhancedCoverReading, shouldIncludeExtraFormat,
                    shouldLoadIdMap = playlistResult.foundPlaylistContent, coverStubUri = coverStubUri
                )
            }
        }
        val mergedPathMap = if (playlistResult.extraPathMap.isEmpty()) {
            result.pathMap
        } else {
            val merged = HashMap<String, MediaItem>()
            result.pathMap?.let { merged.putAll(it) }
            merged.putAll(playlistResult.extraPathMap)
            merged
        }
        // We can null assert because we never pass shouldLoad*=false into Reader
        return SimpleReaderResult(
            result.songList,
            result.albumList!!,
            result.albumArtistList!!,
            result.artistList!!,
            result.genreList!!,
            result.dateList!!,
            playlistResult.playlists.map { it.toPlaylist(result.idMap, mergedPathMap) }.let {
                if (recentlyAddedFilterSecond != null)
                    it + RecentlyAdded(
                        (System.currentTimeMillis() / 1000L) - recentlyAddedFilterSecond,
                        result.songList
                    )
                else it
            },
            result.folderStructure!!,
            result.shallowFolder!!,
            result.folders!!
        )
    }
}
