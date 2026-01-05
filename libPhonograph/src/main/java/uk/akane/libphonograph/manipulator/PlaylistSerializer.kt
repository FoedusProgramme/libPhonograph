package uk.akane.libphonograph.manipulator

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

object PlaylistSerializer {
    private const val TAG = "PlaylistSerializer"
    const val ACCORD_PRIVATE_DIR = "accord_playlists"
    const val ACCORD_ID_PREFIX = "MediaStore:"

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
