package uk.akane.libphonograph.manipulator

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Base64
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.io.File
import java.nio.charset.StandardCharsets
import org.json.JSONObject

object PlaylistSerializer {
    private const val TAG = "PlaylistSerializer"
    const val ACCORD_PRIVATE_DIR = "accord_playlists"
    const val ACCORD_ID_PREFIX = "MediaStore:"
    const val ACCORD_ITEM_PREFIX = "AccordItem:"

    @Throws(UnsupportedPlaylistFormatException::class)
    fun write(context: Context, outFile: File, songs: List<File>) {
        val format = when (outFile.extension.lowercase()) {
            "m3u", "m3u8" -> PlaylistFormat.M3u
            // "xspf" -> PlaylistFormat.Xspf
            // "wpl" -> PlaylistFormat.Wpl
            // "pls" -> PlaylistFormat.Pls
            else -> throw UnsupportedPlaylistFormatException(outFile.extension)
        }
        write(context, format, outFile, songs)
    }

    @Throws(UnsupportedPlaylistFormatException::class)
    fun read(outFile: File): List<File> {
        val format = when (outFile.extension.lowercase()) {
            "m3u", "m3u8" -> PlaylistFormat.M3u
            // "xspf" -> PlaylistFormat.Xspf
            // "wpl" -> PlaylistFormat.Wpl
            // "pls" -> PlaylistFormat.Pls
            else -> throw UnsupportedPlaylistFormatException(outFile.extension)
        }
        return read(format, outFile)
    }

    private fun read(format: PlaylistFormat, outFile: File): List<File> {
        return when (format) {
            PlaylistFormat.M3u -> {
                val lines = outFile.readLines()
                lines.filter { !it.startsWith('#') }
                    .map { Uri.decode(it) }
                    .map { entry -> resolveEntryFile(outFile, entry) }
            }
            PlaylistFormat.Xspf -> TODO()
            PlaylistFormat.Wpl -> TODO()
            PlaylistFormat.Pls -> TODO()
        }
    }

    private fun write(
        context: Context,
        format: PlaylistFormat,
        outFile: File,
        songs: List<File>
    ) {
        when (format) {
            PlaylistFormat.M3u -> {
                if (isAppPrivatePlaylist(context, outFile)) {
                    val entries = songs.map { buildPrivatePlaylistEntry(context, it) }
                    writePrivatePlaylistEntries(outFile, entries)
                } else {
                    val parent = outFile.parentFile ?: throw NullPointerException("parentFile of playlist is null")
                    val lines = mutableListOf("#EXTM3U")
                    songs.forEach { song ->
                        val relative = song.relativeToOrNull(parent)?.toString()
                        lines.add(relative ?: song.absolutePath)
                    }
                    outFile.writeText(lines.joinToString("\n"))
                }
            }
            PlaylistFormat.Xspf -> TODO()
            PlaylistFormat.Wpl -> TODO()
            PlaylistFormat.Pls -> TODO()
        }
        if (!isAppPrivatePlaylist(context, outFile)) {
            MediaScannerConnection.scanFile(context, arrayOf(outFile.toString()), null) { path, uri ->
                if (uri == null) {
                    Log.e(TAG, "failed to scan playlist $path")
                }
            }
        }
    }

    fun getPrivatePlaylistDir(context: Context): File {
        val dir = File(context.filesDir, ACCORD_PRIVATE_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun isAppPrivatePlaylist(context: Context, file: File): Boolean {
        val basePath = runCatching { context.filesDir.canonicalPath }.getOrNull() ?: return false
        val filePath = runCatching { file.canonicalPath }.getOrNull() ?: return false
        return filePath.startsWith(basePath + File.separator)
    }

    fun readPrivatePlaylistEntries(outFile: File): List<String> {
        if (!outFile.exists() || !outFile.isFile) return emptyList()
        return runCatching {
            outFile.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        }.getOrDefault(emptyList())
    }

    fun writePrivatePlaylistEntries(outFile: File, entries: List<String>) {
        val lines = mutableListOf("#EXTM3U")
        entries.filter { it.isNotBlank() }.forEach { lines.add(it) }
        outFile.writeText(lines.joinToString("\n"))
    }

    fun buildPrivatePlaylistEntry(context: Context, song: File): String {
        val id = resolveAudioId(context, song)
        return if (id != null) "$ACCORD_ID_PREFIX$id" else song.absolutePath
    }

    fun buildPrivatePlaylistEntry(item: MediaItem): String? {
        return runCatching {
            val metadata = item.mediaMetadata
            val mediaId = item.mediaId
            if (mediaId.isBlank()) return@runCatching null
            val obj = JSONObject().apply {
                put("media_id", mediaId)
                item.localConfiguration?.uri?.toString()?.let { put("uri", it) }
                item.localConfiguration?.mimeType?.let { put("mime_type", it) }
                item.localConfiguration?.customCacheKey?.let { put("custom_cache_key", it) }
                metadata.title?.toString()?.let { put("title", it) }
                metadata.artist?.toString()?.let { put("artist", it) }
                metadata.albumTitle?.toString()?.let { put("album", it) }
                metadata.albumArtist?.toString()?.let { put("album_artist", it) }
                metadata.artworkUri?.toString()?.let { put("artwork_uri", it) }
                metadata.durationMs?.takeIf { it > 0 }?.let { put("duration_ms", it) }
            }
            val encoded = Base64.encodeToString(
                obj.toString().toByteArray(StandardCharsets.UTF_8),
                Base64.NO_WRAP
            )
            "$ACCORD_ITEM_PREFIX$encoded"
        }.getOrNull()
    }

    fun decodePrivatePlaylistEntry(entry: String): MediaItem? {
        if (!entry.startsWith(ACCORD_ITEM_PREFIX)) return null
        return runCatching {
            val payload = entry.removePrefix(ACCORD_ITEM_PREFIX)
            val decoded = String(
                Base64.decode(payload, Base64.DEFAULT),
                StandardCharsets.UTF_8
            )
            val obj = JSONObject(decoded)
            val mediaId = obj.optString("media_id").orEmpty()
            if (mediaId.isBlank()) return@runCatching null
            val uri = obj.optString("uri").takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
            val mimeType = obj.optString("mime_type").takeIf { it.isNotBlank() }
            val customCacheKey = obj.optString("custom_cache_key").takeIf { it.isNotBlank() }
            val metadata = MediaMetadata.Builder()
                .setTitle(obj.optString("title").takeIf { it.isNotBlank() })
                .setArtist(obj.optString("artist").takeIf { it.isNotBlank() })
                .setAlbumTitle(obj.optString("album").takeIf { it.isNotBlank() })
                .setAlbumArtist(obj.optString("album_artist").takeIf { it.isNotBlank() })
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .apply {
                    val artwork = obj.optString("artwork_uri").takeIf { it.isNotBlank() }
                    if (artwork != null) {
                        setArtworkUri(Uri.parse(artwork))
                    }
                    val duration = obj.optLong("duration_ms", 0L)
                    if (duration > 0) {
                        setDurationMs(duration)
                    }
                }
                .build()
            val builder = MediaItem.Builder()
                .setMediaId(mediaId)
                .setMediaMetadata(metadata)
            if (uri != null) builder.setUri(uri)
            if (mimeType != null) builder.setMimeType(mimeType)
            if (customCacheKey != null) builder.setCustomCacheKey(customCacheKey)
            builder.build()
        }.getOrNull()
    }

    fun resolveEntryFile(outFile: File, entry: String): File {
        val file = File(entry)
        val resolved = if (file.isAbsolute) file else outFile.resolveSibling(entry)
        if (!resolved.isAbsolute && !resolved.exists() &&
            (entry.startsWith("storage/") || entry.startsWith("sdcard/"))
        ) {
            return File("/$entry")
        }
        return resolved
    }

    fun resolveAudioId(context: Context, file: File): Long? {
        val resolver = context.contentResolver
        val directId = runCatching {
            @Suppress("DEPRECATION")
            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.DATA} = ?",
                arrayOf(file.absolutePath),
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor.getLong(0)
            }
        }.getOrNull()
        if (directId != null) return directId

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val base = Environment.getExternalStorageDirectory()
        val basePath = runCatching { base.canonicalPath }.getOrNull() ?: return null
        val filePath = runCatching { file.canonicalPath }.getOrNull() ?: return null
        if (!filePath.startsWith(basePath + File.separator)) return null
        val relativeFull = filePath.removePrefix(basePath + File.separator)
        val relativeDir = relativeFull.substringBeforeLast("/", "")
        val relativePath = if (relativeDir.isEmpty()) "" else "$relativeDir/"
        val displayName = file.name
        return runCatching {
            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.RELATIVE_PATH} = ? AND ${MediaStore.Audio.Media.DISPLAY_NAME} = ?",
                arrayOf(relativePath, displayName),
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor.getLong(0)
            }
        }.getOrNull()
    }

    private enum class PlaylistFormat {
        M3u,
        Xspf,
        Wpl,
        Pls,
    }

    class UnsupportedPlaylistFormatException(extension: String) : Exception(extension)
}
