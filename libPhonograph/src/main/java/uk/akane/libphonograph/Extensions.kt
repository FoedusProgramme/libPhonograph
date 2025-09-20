package uk.akane.libphonograph

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.ext.SdkExtensions
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import androidx.media3.common.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.experimental.ExperimentalTypeInference

internal inline fun <reified T, reified U> HashMap<T, U>.putIfAbsentSupport(key: T, value: U) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        putIfAbsent(key, value)
    } else {
        // Duh...
        if (!containsKey(key))
            put(key, value)
    }
}

abstract class ContentObserverCompat(handler: Handler?) : ContentObserver(handler) {
    final override fun onChange(selfChange: Boolean) {
        onChange(selfChange, null)
    }

    final override fun onChange(selfChange: Boolean, uri: Uri?) {
        onChange(selfChange, uri, 0)
    }

    final override fun onChange(selfChange: Boolean, uri: Uri?, flags: Int) {
        if (uri == null)
            onChange(selfChange, emptyList(), flags)
        else
            onChange(selfChange, listOf(uri), flags)
    }

    abstract override fun onChange(selfChange: Boolean, uris: Collection<Uri>, flags: Int)
    abstract override fun deliverSelfNotifications(): Boolean
}

@OptIn(ExperimentalTypeInference::class)
internal fun versioningCallbackFlow(
    @BuilderInference block: suspend ProducerScope<Long>.(() -> Long) -> Unit
): Flow<Long> {
    val versionTracker = AtomicLong()
    return callbackFlow { block(versionTracker::incrementAndGet) }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun contentObserverVersioningFlow(
    context: Context, scope: CoroutineScope, uri: Uri,
    notifyForDescendants: Boolean
): Flow<Long> {
    return versioningCallbackFlow { nextVersion ->
        val listener = object : ContentObserverCompat(null) {
            override fun onChange(selfChange: Boolean, uris: Collection<Uri>, flags: Int) {
                // TODO can we use those uris and flags for incremental reload at least on newer
                //  platform versions? completely since R+, Q has no flags, before we get meh deletion handling
                scope.launch {
                    send(nextVersion())
                }
            }

            override fun deliverSelfNotifications(): Boolean {
                return true
            }
        }
        // Notifications may get delayed while we are frozen, but they do not get lost. Though, if
        // too many of them pile up, we will get killed for eating too much space with our async
        // binder transactions and we will have to restart in a new process later.
        context.contentResolver.registerContentObserver(uri, notifyForDescendants, listener)
        send(nextVersion())
        awaitClose {
            context.contentResolver.unregisterContentObserver(listener)
        }
    }
}

fun File.toUriCompat(): Uri {
    val tmp = Uri.fromFile(this)
    return if (tmp.scheme != "file") // weird os bug workaround, found on Samsung and Xiaomi
        tmp.buildUpon().scheme("file").build()
    else tmp
}

fun MediaItem.getUri(): Uri? {
    return localConfiguration?.uri
}

fun MediaItem.getFile(): File? {
    return getUri()?.toFile()
}

@Suppress("NOTHING_TO_INLINE")
inline fun Context.hasAudioPermission() =
    hasScopedStorageWithMediaTypes() && ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.READ_MEDIA_AUDIO
    ) == PackageManager.PERMISSION_GRANTED ||
            (!hasScopedStorageV2() && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED) ||
            (!hasScopedStorageWithMediaTypes() && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED)

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun Context.hasImagePermission() =
    checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
@Suppress("NOTHING_TO_INLINE")
inline fun hasImprovedMediaStore(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

@Suppress("NOTHING_TO_INLINE")
inline fun hasMarkIsFavouriteStatus(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 16)

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
@Suppress("NOTHING_TO_INLINE")
inline fun hasScopedStorageV2(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
@Suppress("NOTHING_TO_INLINE")
inline fun hasScopedStorageWithMediaTypes(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

@Suppress("NOTHING_TO_INLINE")
internal inline fun Cursor.getStringOrNullIfThrow(index: Int): String? =
    try {
        getString(index)
    } catch (_: Exception) {
        null
    }

@Suppress("NOTHING_TO_INLINE")
internal inline fun Cursor.getLongOrNullIfThrow(index: Int): Long? =
    try {
        getLong(index)
    } catch (_: Exception) {
        null
    }

@Suppress("NOTHING_TO_INLINE")
internal inline fun Cursor.getIntOrNullIfThrow(index: Int): Int? =
    try {
        getInt(index)
    } catch (_: Exception) {
        null
    }

@Suppress("NOTHING_TO_INLINE")
internal inline fun Cursor.getColumnIndexOrNull(columnName: String): Int? =
    getColumnIndex(columnName).let { if (it == -1) null else it }

// From https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui-util/src/commonMain/kotlin/androidx/compose/ui/util/

/**
 * Iterates through a [List] using the index and calls [action] for each item. This does not
 * allocate an iterator like [Iterable.forEach].
 *
 * **Do not use for collections that come from public APIs**, since they may not support random
 * access in an efficient way, and this method may actually be a lot slower. Only use for
 * collections that are created by code we control and are known to support random access.
 */
@Suppress("BanInlineOptIn")
@OptIn(ExperimentalContracts::class)
inline fun <T> List<T>.fastForEach(action: (T) -> Unit) {
    contract { callsInPlace(action) }
    for (index in indices) {
        val item = get(index)
        action(item)
    }
}

/**
 * Returns a list containing all elements that are not null
 *
 * **Do not use for collections that come from public APIs**, since they may not support random
 * access in an efficient way, and this method may actually be a lot slower. Only use for
 * collections that are created by code we control and are known to support random access.
 */
fun <T : Any> List<T?>.fastFilterNotNull(): List<T> {
    val target = ArrayList<T>(size)
    fastForEach { if ((it) != null) target += (it) }
    return target
}
