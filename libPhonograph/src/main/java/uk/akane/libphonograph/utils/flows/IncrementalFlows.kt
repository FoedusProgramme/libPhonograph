package uk.akane.libphonograph.utils.flows

import android.content.ContentUris
import android.net.Uri
import androidx.media3.common.MediaItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted.Companion.WhileSubscribed
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uk.akane.libphonograph.Constants
import uk.akane.libphonograph.getFile
import uk.akane.libphonograph.items.albumId
import uk.akane.libphonograph.items.artistId
import uk.akane.libphonograph.toUriCompat
import uk.akane.libphonograph.utils.MiscUtils
import uk.akane.libphonograph.utils.MiscUtils.findBestCover
import uk.akane.libphonograph.utils.flows.PauseManagingSharedFlow.Companion.sharePauseableIn
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.min

sealed class IncrementalList<T>(val after: List<T>) {
    class Begin<T>(after: List<T>) : IncrementalList<T>(after) {
        override fun toString() = "Begin()"
    }
    class Insert<T>(val pos: Int, val count: Int, after: List<T>) : IncrementalList<T>(after) {
        override fun toString() = "Insert(pos=$pos, count=$count)"
    }
    class Remove<T>(val pos: Int, val count: Int, after: List<T>) : IncrementalList<T>(after) {
        override fun toString() = "Remove(pos=$pos, count=$count)"
    }
    class Move<T>(val pos: Int, val count: Int, val outPos: Int, after: List<T>) : IncrementalList<T>(after) {
        override fun toString() = "Move(pos=$pos, count=$count, outPos=$outPos)"
    }
    class Update<T>(val pos: Int, val count: Int, after: List<T>) : IncrementalList<T>(after) {
        override fun toString() = "Update(pos=$pos, count=$count)"
    }
}

sealed class IncrementalMap<T, R>(val after: Map<T, R>) {
    class Begin<T, R>(after: Map<T, R>) : IncrementalMap<T, R>(after)
    class Insert<T, R>(val key: T, after: Map<T, R>) : IncrementalMap<T, R>(after)
    class Remove<T, R>(val key: T, after: Map<T, R>) : IncrementalMap<T, R>(after)
    class Move<T, R>(val key: T, val outKey: T, after: Map<T, R>) : IncrementalMap<T, R>(after)
    class Update<T, R>(val key: T, after: Map<T, R>) : IncrementalMap<T, R>(after)
}

inline fun <T, R> Flow<IncrementalList<T>>.flatMapIncremental(
    crossinline predicate: (T) -> List<R>
): Flow<IncrementalList<R>> = flow {
    var last: List<List<R>>? = null
    var lastFlat: List<R>? = null
    collect { command ->
        var new: List<List<R>>
        var newFlat: List<R>? = null
        when {
            command is IncrementalList.Begin || last == null -> {
                new = command.after.map(predicate)
                newFlat = new.flatMap { it }
                emit(IncrementalList.Begin(newFlat))
            }
            command is IncrementalList.Insert -> {
                new = ArrayList(last!!)
                var totalSize = 0
                for (i in command.pos..<command.pos+command.count) {
                    val item = predicate(command.after[i])
                    totalSize += item.size
                    new.add(i, item)
                }
                if (totalSize > 0) {
                    var totalStart = 0
                    for (i in 0..<command.pos) {
                        totalStart += new[i].size
                    }
                    newFlat = new.flatMap { it }
                    emit(IncrementalList.Insert(totalStart, totalSize, newFlat))
                }
            }
            command is IncrementalList.Move -> {
                new = ArrayList(last!!)
                var totalSize = 0
                repeat(command.count) { _ ->
                    totalSize += new.removeAt(command.pos).size
                }
                for (i in command.outPos..<command.outPos+command.count) {
                    new.add(i, last!![i - command.outPos + command.pos])
                }
                if (totalSize > 0) {
                    var totalStart = 0
                    for (i in 0..<command.pos) {
                        totalStart += last!![i].size
                    }
                    var totalOutStart = 0
                    for (i in 0..<command.outPos) {
                        totalOutStart += new[i].size
                    }
                    newFlat = new.flatMap { it }
                    emit(IncrementalList.Move(totalStart, totalSize, totalOutStart, newFlat))
                }
            }
            command is IncrementalList.Remove -> {
                new = ArrayList(last!!)
                var totalSize = 0
                for (i in command.pos..<command.pos+command.count) {
                    totalSize += new.removeAt(i).size
                }
                if (totalSize > 0) {
                    var totalStart = 0
                    for (i in 0..<command.pos) {
                        totalStart += new[i].size
                    }
                    newFlat = new.flatMap { it }
                    emit(IncrementalList.Remove(totalStart, totalSize, newFlat))
                }
            }
            command is IncrementalList.Update -> {
                new = ArrayList(last!!)
                var removed = 0
                var added = 0
                for (i in command.pos..<command.pos+command.count) {
                    removed += new[i].size
                    val item = predicate(command.after[i])
                    added += item.size
                    new[i] = item
                }
                if (removed != 0 || added != 0) {
                    var baseStart = 0
                    for (i in 0..<command.pos) {
                        baseStart += new[i].size
                    }
                    val baseSize = min(added, removed)
                    val offsetStart = baseStart + baseSize
                    var offsetCount = abs(added - removed)
                    newFlat = new.flatMap { it }
                    // in insert/remove cases we technically spoiler updates to the list but it doesn't matter
                    if (removed > added) {
                        emit(IncrementalList.Remove(offsetStart, offsetCount, newFlat))
                    } else if (removed < added) {
                        emit(IncrementalList.Insert(offsetStart, offsetCount, newFlat))
                    }
                    if (removed != 0 && added != 0) {
                        emit(IncrementalList.Update(baseStart, baseSize, newFlat))
                    }
                }
            }
            else -> throw IllegalArgumentException("code bug, IncrementalCommand case exhausted")
        }
        last = new
        lastFlat = newFlat ?: lastFlat
    }
}

inline fun <T> Flow<IncrementalList<T>>.filterIncremental(
    crossinline predicate: (T) -> Boolean
): Flow<IncrementalList<T>> = flatMapIncremental {
    if (predicate(it)) listOf(it) else emptyList()
}

/*
   Hand-"optimized" version of:
     inline fun <T, R> Flow<IncrementalCommand<T>>.mapIncremental(
         crossinline predicate: (T) -> R
     ): Flow<IncrementalCommand<R>> = flatMapIncremental {
         listOf(predicate(it))
     }
 */
inline fun <T, R> Flow<IncrementalList<T>>.mapIncremental(
    crossinline predicate: (T) -> R
): Flow<IncrementalList<R>> = flow {
    var last: List<R>? = null
    collect { command ->
        var new: List<R>
        when {
            command is IncrementalList.Begin || last == null -> {
                new = command.after.map(predicate)
                emit(IncrementalList.Begin(new))
            }
            command is IncrementalList.Insert -> {
                new = ArrayList(last!!)
                for (i in command.pos..<command.pos+command.count) {
                    new.add(i, predicate(command.after[i]))
                }
                emit(IncrementalList.Insert(command.pos, command.count, new))
            }
            command is IncrementalList.Move -> {
                new = ArrayList(last!!)
                repeat(command.count) { _ ->
                    new.removeAt(command.pos)
                }
                for (i in command.outPos..<command.outPos+command.count) {
                    new.add(i, last!![i - command.outPos + command.pos])
                }
                emit(IncrementalList.Move(command.pos, command.count, command.outPos, new))
            }
            command is IncrementalList.Remove -> {
                new = ArrayList(last!!)
                repeat(command.count) { _ ->
                    new.removeAt(command.pos)
                }
                emit(IncrementalList.Remove(command.pos, command.count, new))
            }
            command is IncrementalList.Update -> {
                new = ArrayList(last!!)
                for (i in command.pos..<command.pos+command.count) {
                    new[i] = predicate(command.after[i])
                }
                emit(IncrementalList.Update(command.pos, command.count, new))
            }
            else -> throw IllegalArgumentException("code bug, IncrementalCommand case exhausted")
        }
        last = new
    }
}

inline fun <T, K> Iterable<T>.groupByIndexed(keySelector: (Int, T) -> K): Map<K, List<T>> {
    val destination = LinkedHashMap<K, ArrayList<T>>()
    forEachIndexed { index, element ->
        val key = keySelector(index, element)
        val list = destination.getOrPut(key) { ArrayList() }
        list.add(element)
    }
    return destination
}

inline fun <T, R> Flow<IncrementalList<T>>.groupByIncremental(
    crossinline getKey: (T) -> R
): Flow<IncrementalMap<R, IncrementalList<T>>> = flow {
    var keyCache: ArrayList<R>? = null
    val groupCache = HashMap<R, IncrementalList<T>>()
    collect { list ->
        when {
            list is IncrementalList.Begin || keyCache == null -> {
                groupCache.clear()
                if (keyCache != null)
                    keyCache!!.clear()
                else
                    keyCache = ArrayList()
                keyCache.addAll(list.after.map { getKey(it) })
                groupCache.putAll(list.after.groupByIndexed { i, _ -> keyCache[i] }
                    .mapValues { IncrementalList.Begin(it.value) })
                emit(IncrementalMap.Begin(groupCache))
            }
            list is IncrementalList.Insert -> {
                for (i in list.pos..<list.pos + list.count) {
                    val item = list.after[i]
                    val key = getKey(item)
                    keyCache.add(i, key)
                    val group = groupCache[key]
                    if (group != null) {
                        var totalStart = 0
                        for (j in list.pos - 1 downTo 0) {
                            if (keyCache[j] == key) {// TODO optimize equals?
                                totalStart = group.after.indexOf(list.after[j])
                                break
                            }
                        }
                        totalStart++
                        val new = group.after.toMutableList().apply { add(totalStart, item) }.toList()
                        groupCache[key] = IncrementalList.Insert(totalStart, 1, new)
                        emit(IncrementalMap.Update(key, groupCache.toMap()))
                    } else {
                        groupCache[key] = IncrementalList.Begin(listOf(item))
                        emit(IncrementalMap.Insert(key, groupCache.toMap()))
                    }
                }
            }
            list is IncrementalList.Move -> {
                var keys = mutableListOf<R>()
                repeat(list.count) { _ ->
                    keys.add(keyCache.removeAt(list.pos))
                }
                for (i in list.outPos..<list.outPos+list.count) {
                    keyCache.add(i, keys[i - list.outPos])
                }
                for (i in list.pos..<list.pos + list.count) {
                    val outPos = list.outPos - list.pos + i
                    val item = list.after[outPos]
                    val key = keys[i - list.pos]
                    val group = groupCache.getValue(key)
                    val oldInnerPos = group.after.indexOf(item)
                    var totalStart = 0
                    for (j in outPos - 1 downTo 0) {
                        if (keyCache[j] == key) {// TODO optimize equals?
                            totalStart = group.after.indexOf(list.after[j])
                            break
                        }
                    }
                    totalStart++
                    val new = group.after.toMutableList().apply { add(totalStart, removeAt(oldInnerPos)) }.toList()
                    groupCache[key] = IncrementalList.Move(oldInnerPos, totalStart, 1, new)
                    emit(IncrementalMap.Update(key, groupCache.toMap()))
                }
            }
            list is IncrementalList.Remove -> {
                repeat(list.count) { _ ->
                    val key = keyCache.removeAt(list.pos)
                    val group = groupCache.getValue(key)
                    if (group.after.size > 1) {
                        var totalStart = 0
                        for (j in list.pos - 1 downTo 0) {
                            if (keyCache[j] == key) {// TODO optimize equals?
                                totalStart = group.after.indexOf(list.after[j])
                                break
                            }
                        }
                        totalStart++
                        val new = group.after.toMutableList().apply { removeAt(totalStart) }.toList()
                        groupCache[key] = IncrementalList.Remove(totalStart, 1, new)
                        emit(IncrementalMap.Update(key, groupCache.toMap()))
                    } else {
                        groupCache.remove(key)
                        emit(IncrementalMap.Remove(key, groupCache.toMap()))
                    }
                }
            }
            list is IncrementalList.Update -> {
                for (i in list.pos..<list.pos + list.count) {
                    val item = list.after[i]
                    val newKey = getKey(item)
                    val oldKey = keyCache[i]
                    val oldGroup = groupCache.getValue(oldKey)
                    if (newKey == oldKey) {
                        var totalStart = 0
                        for (j in list.pos - 1 downTo 0) {
                            if (keyCache[j] == oldKey) {// TODO optimize equals?
                                totalStart = oldGroup.after.indexOf(list.after[j])
                                break
                            }
                        }
                        totalStart++
                        val new = oldGroup.after.toMutableList().apply { this[totalStart] = item }.toList()
                        groupCache[oldKey] = IncrementalList.Update(i, 1, new)
                        emit(IncrementalMap.Update(oldKey, groupCache.toMap()))
                        continue
                    }
                    keyCache[i] = newKey
                    if (oldGroup.after.size > 1) {
                        var totalStart = 0
                        for (j in list.pos - 1 downTo 0) {
                            if (keyCache[j] == oldKey) {// TODO optimize equals?
                                totalStart = oldGroup.after.indexOf(list.after[j])
                                break
                            }
                        }
                        totalStart++
                        val new = oldGroup.after.toMutableList().apply { removeAt(totalStart) }.toList()
                        groupCache[oldKey] = IncrementalList.Remove(totalStart, 1, new)
                        emit(IncrementalMap.Update(oldKey, groupCache.toMap()))
                    } else {
                        groupCache.remove(oldKey)
                        emit(IncrementalMap.Remove(oldKey, groupCache.toMap()))
                    }
                    val group = groupCache[newKey]
                    if (group != null) {
                        var totalStart = 0
                        for (j in list.pos - 1 downTo 0) {
                            if (keyCache[j] == newKey) {// TODO optimize equals?
                                totalStart = group.after.indexOf(list.after[j])
                                break
                            }
                        }
                        totalStart++
                        val new = group.after.toMutableList().apply { add(totalStart, item) }.toList()
                        groupCache[newKey] = IncrementalList.Insert(totalStart, 1, new)
                        emit(IncrementalMap.Update(newKey, groupCache.toMap()))
                    } else {
                        groupCache[newKey] = IncrementalList.Begin(listOf(item))
                        emit(IncrementalMap.Insert(newKey, groupCache.toMap()))
                    }
                }
            }
        }
    }
}

@Suppress("NOTHING_TO_INLINE")
private suspend inline fun <T, R> ProducerScope<IncrementalMap<T, R>>.mergeCollector(
    lock: Mutex, state: HashMap<T, R>,
    otherState: HashMap<T, R>,
    otherWinsConflict: Boolean,
    it: IncrementalMap<T, R>
) {
    lock.withLock {
        when (it) {
            is IncrementalMap.Begin -> {
                state.clear()
                state.putAll(it.after)
                send(IncrementalMap.Begin(if (otherWinsConflict) state + otherState else otherState + state))
            }

            is IncrementalMap.Insert -> {
                state[it.key] = @Suppress("UNCHECKED_CAST") (it.after[it.key] as R)
                if (otherState.contains(it.key)) {
                    if (!otherWinsConflict) {
                        send(IncrementalMap.Update(it.key, otherState + state))
                    }
                } else {
                    send(IncrementalMap.Insert(it.key, if (otherWinsConflict) state + otherState else
                        otherState + state))
                }
            }

            is IncrementalMap.Move -> {
                state[it.outKey] = @Suppress("UNCHECKED_CAST") (state.remove(it.key) as R)
                if (otherWinsConflict) {
                    val containsOld = otherState.contains(it.key)
                    val containsNew = otherState.contains(it.outKey)
                    if (containsOld != containsNew) {
                        if (containsOld) {
                            send(IncrementalMap.Insert(it.outKey, state + otherState))
                        } else {
                            send(IncrementalMap.Remove(it.key, state + otherState))
                        }
                    } else if (!containsOld /* && !containsNew */) {
                        send(IncrementalMap.Move(it.key, it.outKey, state + otherState))
                    }
                } else {
                    if (otherState.contains(it.outKey)) {
                        send(IncrementalMap.Remove(it.outKey, otherState + state))
                    }
                    send(IncrementalMap.Move(it.key, it.outKey, otherState + state))
                    if (otherState.contains(it.key)) {
                        send(IncrementalMap.Insert(it.key, otherState + state))
                    }
                }
            }

            is IncrementalMap.Remove -> {
                state.remove(it.key)
                if (otherState.contains(it.key)) {
                    if (!otherWinsConflict) {
                        send(IncrementalMap.Update(it.key, otherState + state))
                    }
                } else {
                    send(IncrementalMap.Remove(it.key, if (otherWinsConflict) state + otherState else
                        otherState + state))
                }
            }

            is IncrementalMap.Update -> {
                state[it.key] = @Suppress("UNCHECKED_CAST") (it.after[it.key] as R)
                if (!otherWinsConflict || !otherState.contains(it.key)) {
                    send(IncrementalMap.Update(it.key, if (otherWinsConflict) state + otherState else
                        otherState + state))
                }
            }
        }
    }
}

fun <T, R> Flow<IncrementalMap<T, R>>.mergeWithIncremental(
    other: Flow<IncrementalMap<T, R>>,
    otherWinsConflict: Boolean = false
): Flow<IncrementalMap<T, R>> = channelFlow {
    coroutineScope {
        val lock = Mutex()
        var state1: HashMap<T, R>? = null
        var state2: HashMap<T, R>? = null
        val job1 = launch {
            this@mergeWithIncremental.collect {
                if (state1 == null) {
                    state1 = HashMap()
                    if (state2 != null) {
                        val cmd = IncrementalMap.Begin(it.after)
                        mergeCollector(lock, state1, state2!!, otherWinsConflict, cmd)
                    }
                } else if (state2 != null) {
                    mergeCollector(lock, state1, state2!!, otherWinsConflict, it)
                }
            }
        }
        val job2 = launch {
            other.collect {
                if (state2 == null) {
                    state2 = HashMap()
                    if (state1 != null) {
                        val cmd = IncrementalMap.Begin(it.after)
                        mergeCollector(lock, state2, state1, !otherWinsConflict, cmd)
                    }
                } else if (state1 != null) {
                    mergeCollector(lock, state2, state1, !otherWinsConflict, it)
                }
            }
        }
        job1.join()
        job2.join()
    }
}.drop(1) // drop the first Begin and wait for the second

inline fun <T, R> Flow<IncrementalMap<T, R>>.filterIncremental(
    crossinline predicate: (T, R) -> Boolean
): Flow<IncrementalMap<T, R>> = flow {
    var filterCache: HashMap<T, Boolean>? = null
    collect { map ->
        when {
            map is IncrementalMap.Begin || filterCache == null -> {
                if (filterCache != null)
                    filterCache!!.clear()
                else
                    filterCache = HashMap()
                filterCache.putAll(map.after.mapValues { predicate(it.key, it.value) })
                emit(IncrementalMap.Begin(map.after.filter { filterCache.getValue(it.key) }))
            }
            map is IncrementalMap.Insert -> {
                val filtered = predicate(map.key, @Suppress("UNCHECKED_CAST") (map.after[map.key] as R))
                filterCache[map.key] = filtered
                if (!filtered)
                    emit(IncrementalMap.Insert(map.key, map.after.filter { filterCache.getValue(it.key) }))
            }
            map is IncrementalMap.Move -> {
                val filtered = filterCache.remove(map.key)!!
                filterCache[map.outKey] = filtered
                if (!filtered)
                    emit(IncrementalMap.Move(map.key, map.outKey, map.after.filter { filterCache.getValue(it.key) }))
            }
            map is IncrementalMap.Remove -> {
                if (!filterCache.remove(map.key)!!)
                    emit(IncrementalMap.Remove(map.key, map.after.filter { filterCache.getValue(it.key) }))
            }
            map is IncrementalMap.Update -> {
                val wasFiltered = filterCache.getValue(map.key)
                val filtered = predicate(map.key, @Suppress("UNCHECKED_CAST") (map.after[map.key] as R))
                if (wasFiltered != filtered) {
                    filterCache[map.key] = filtered
                    if (wasFiltered) {
                        emit(IncrementalMap.Insert(map.key, map.after.filter { filterCache.getValue(it.key) }))
                    } else /* if (filtered) */ {
                        emit(IncrementalMap.Remove(map.key, map.after.filter { filterCache.getValue(it.key) }))
                    }
                } else if (!filtered) {
                    emit(IncrementalMap.Update(map.key, map.after.filter { filterCache.getValue(it.key) }))
                }
            }
        }
    }
}

inline fun <T, R, S> Flow<IncrementalMap<T, R>>.mapNonCachedIncremental(
    crossinline fastPredicate: (T, R) -> S
): Flow<IncrementalMap<T, S>> = flow {
    collect { map ->
        when (map) {
            is IncrementalMap.Begin -> {
                emit(IncrementalMap.Begin(map.after.mapValues { fastPredicate(it.key, it.value) }))
            }
            is IncrementalMap.Insert -> {
                emit(IncrementalMap.Insert(map.key, map.after.mapValues { fastPredicate(it.key, it.value) }))
            }
            is IncrementalMap.Move -> {
                emit(IncrementalMap.Move(map.key, map.outKey, map.after.mapValues { fastPredicate(it.key, it.value) }))
            }
            is IncrementalMap.Remove -> {
                emit(IncrementalMap.Remove(map.key, map.after.mapValues { fastPredicate(it.key, it.value) }))
            }
            is IncrementalMap.Update -> {
                emit(IncrementalMap.Update(map.key, map.after.mapValues { fastPredicate(it.key, it.value) }))
            }
        }
    }
}

inline fun <T, R, S> Flow<IncrementalMap<T, R>>.mapIncremental(
    crossinline predicate: (T, R) -> S
): Flow<IncrementalMap<T, S>> = flow {
    var mapCache: HashMap<T, S>? = null
    collect { map ->
        when {
            map is IncrementalMap.Begin || mapCache == null -> {
                if (mapCache != null)
                    mapCache!!.clear()
                else
                    mapCache = HashMap()
                mapCache.putAll(map.after.mapValues { predicate(it.key, it.value) })
                emit(IncrementalMap.Begin(map.after.mapValues { mapCache.getValue(it.key) }))
            }
            map is IncrementalMap.Insert -> {
                mapCache[map.key] = predicate(map.key, @Suppress("UNCHECKED_CAST") (map.after[map.key] as R))
                emit(IncrementalMap.Insert(map.key, map.after.mapValues { mapCache.getValue(it.key) }))
            }
            map is IncrementalMap.Move -> {
                mapCache[map.outKey] = mapCache.remove(map.key)!!
                emit(IncrementalMap.Move(map.key, map.outKey, map.after.mapValues { mapCache.getValue(it.key) }))
            }
            map is IncrementalMap.Remove -> {
                mapCache.remove(map.key)
                emit(IncrementalMap.Remove(map.key, map.after.mapValues { mapCache.getValue(it.key) }))
            }
            map is IncrementalMap.Update -> {
                mapCache[map.key] = predicate(map.key, @Suppress("UNCHECKED_CAST") (map.after[map.key] as R))
                emit(IncrementalMap.Update(map.key, map.after.mapValues { mapCache.getValue(it.key) }))
            }
        }
    }
}

inline fun <T, R> Flow<IncrementalMap<T, R>>.filterLatestIncremental(
    crossinline predicate: (T, R) -> Flow<Boolean>
): Flow<IncrementalMap<T, R>> = mapIncremental { a, b ->
    predicate(a, b).map { b to it }
}.flattenIncremental().filterIncremental { _, b -> b.second }.mapNonCachedIncremental { _, b -> b.first }

@PublishedApi
internal inline fun <T, R> CoroutineScope.createFlattenJob(
    key: R, flow: Flow<T>, crossinline update: suspend (() -> R, T) -> Unit
): Pair<Job, AtomicReference<R>> {
    val keyReference = AtomicReference(key)
    return launch {
        flow.collect {
            update({ keyReference.get() }, it)
        }
    } to keyReference
}

@PublishedApi
internal sealed class PendingCommand {
    private val deferred = CompletableDeferred<Unit>()
    fun complete() = deferred.complete(Unit)
    suspend fun await() = deferred.await()
    class Begin : PendingCommand()
    class Insert : PendingCommand()
    class Update : PendingCommand()
}

@Suppress("NOTHING_TO_INLINE")
inline fun <T, R> Flow<IncrementalMap<T, Flow<R>>>.flattenIncremental(): Flow<IncrementalMap<T, R>> = channelFlow {
    coroutineScope {
        val lock = Mutex()
        var state: HashMap<T, Pair<Job, AtomicReference<T>>>? = null
        val outputState = HashMap<T, R>()
        val pendingKeys = HashSet<T>()
        var pending: PendingCommand? = null
        val update: suspend (getKeyLocked: () -> T, value: R) -> Unit = { getKeyLocked, b ->
            lock.withLock {
                val a = getKeyLocked()
                outputState[a] = b
                if (pending != null && pendingKeys.contains(a)) {
                    pendingKeys.remove(a)
                    if (pendingKeys.isEmpty()) {
                        when (pending!!) {
                            is PendingCommand.Begin -> send(IncrementalMap.Begin(outputState.toMap()))
                            is PendingCommand.Insert -> send(IncrementalMap.Insert(a, outputState.toMap()))
                            is PendingCommand.Update -> send(IncrementalMap.Update(a, outputState.toMap()))
                        }
                        pending!!.complete()
                        pending = null
                    }
                } else {
                    send(IncrementalMap.Update(a, outputState.toMap()))
                }
            }
        }
        collect { map ->
            when {
                map is IncrementalMap.Begin || state == null -> {
                    if (state != null) {
                        state!!.forEach { it.value.first.cancel() }
                        // must join all to avoid old jobs accessing outputState
                        state!!.forEach { it.value.first.join() }
                        state!!.clear()
                        outputState.clear()
                    } else state = HashMap()
                    val deferred = PendingCommand.Begin()
                    pending = deferred
                    state.putAll(map.after.mapValues { createFlattenJob(it.key, it.value, update) })
                    deferred.await()
                }
                map is IncrementalMap.Insert -> {
                    val deferred = PendingCommand.Insert()
                    pending = deferred
                    state[map.key] = createFlattenJob(map.key, map.after.getValue(map.key), update)
                    deferred.await()
                }
                map is IncrementalMap.Move -> {
                    lock.withLock {
                        val item = state.remove(map.key)!!
                        item.second.set(map.outKey)
                        state[map.outKey] = item
                        outputState[map.outKey] = @Suppress("UNCHECKED_CAST") (outputState.remove(map.key) as R)
                        send(IncrementalMap.Move(map.key, map.outKey, outputState.toMap()))
                    }
                }
                map is IncrementalMap.Remove -> {
                    lock.withLock {
                        state.remove(map.key)!!.first.cancelAndJoin()
                        outputState.remove(map.key)
                        send(IncrementalMap.Remove(map.key, outputState))
                    }
                }
                map is IncrementalMap.Update -> {
                    lock.withLock {
                        state.remove(map.key)!!.first.cancelAndJoin()
                    }
                    val deferred = PendingCommand.Update()
                    pending = deferred
                    state[map.key] = createFlattenJob(map.key, map.after.getValue(map.key), update)
                    deferred.await()
                }
            }
        }
    }
}

@Suppress("NOTHING_TO_INLINE")
inline fun <T, R> Flow<IncrementalMap<T, R>>.toIncrementalList(
    noinline fastComparator: (T, T) -> Int
): Flow<IncrementalList<R>> = flow {
    var keys: ArrayList<T>? = null
    val values = ArrayList<R>()

    collect { event ->
        when {
            event is IncrementalMap.Begin || keys == null -> {
                if (keys != null)
                    keys!!.clear()
                else
                    keys = ArrayList()
                values.clear()
                event.after.keys.sortedWith(fastComparator).forEach { k ->
                    keys.add(k)
                    values.add(event.after.getValue(k))
                }
                emit(IncrementalList.Begin(values.toList()))
            }
            event is IncrementalMap.Insert -> {
                val key = event.key
                val value = event.after.getValue(key)
                val idx = keys.binarySearch(key, fastComparator).let {
                    if (it >= 0) it else -it - 1
                }
                keys.add(idx, key)
                values.add(idx, value)
                emit(IncrementalList.Insert(idx, 1, values.toList()))
            }
            event is IncrementalMap.Update -> {
                val key = event.key
                val value = event.after.getValue(key)
                val idx = keys.indexOf(key).takeIf { it >= 0 }
                    ?: throw IllegalStateException("Key not found for update: $key")
                values[idx] = value
                emit(IncrementalList.Update(idx, 1, values.toList()))
            }
            event is IncrementalMap.Move -> {
                val oldKey = event.key
                val newKey = event.outKey
                val fromIdx = keys.indexOf(oldKey).takeIf { it >= 0 }
                    ?: throw IllegalStateException("Key not found for move: $oldKey")
                keys.removeAt(fromIdx)
                val movedValue = values.removeAt(fromIdx)
                val toIdx = keys.binarySearch(newKey, fastComparator).let {
                    if (it >= 0) it else -it - 1
                }
                keys.add(toIdx, newKey)
                values.add(toIdx, movedValue)
                emit(IncrementalList.Move(fromIdx, toIdx, 1, values.toList()))
            }
            event is IncrementalMap.Remove -> {
                val key = event.key
                val idx = keys.indexOf(key).takeIf { it >= 0 }
                    ?: throw IllegalStateException("Key not found for remove: $key")
                keys.removeAt(idx)
                values.removeAt(idx)
                emit(IncrementalList.Remove(idx, 1, values.toList()))
            }
        }
    }
}

@Suppress("NOTHING_TO_INLINE")
inline fun <T, R : Any> Flow<IncrementalMap<T, R>>.forKey(
    key: T
): Flow<R?> = flow {
    collect {
        val shouldEmit = when (it) {
            // TODO will Begin handling lead to correct behaviour combined with groupBy and merge
            is IncrementalMap.Begin -> true
            is IncrementalMap.Insert -> it.key == key
            is IncrementalMap.Move -> it.key == key || it.outKey == key
            is IncrementalMap.Remove -> it.key == key
            is IncrementalMap.Update -> it.key == key
        }
        if (shouldEmit)
            emit(it.after[key])
    }
}

// TODO unit tests

// Basic pattern:
data class Album2(
    val id: Long?,
    val title: String?,
    val albumArtist: String?,
    val albumArtistId: Long?,
    val albumYear: Int?, // Last year
    val cover: Uri?,
    val songCount: Int,
)
data class Artist2(
    val id: Long?,
    val name: String?,
    val cover: Uri?,
    val songCount: Int,
    val albumCount: Int,
)

// TODO sharePauseableIn should propagate replay cache invalidation to downstream as well
private data class ReaderResult2(
    val songList: IncrementalList<MediaItem>,
    val canonicalArtistIdMap: Map<String, Long>,
)
private var useEnhancedCoverReading = true
private var coverStubUri: String? = "gramophoneCover"//TODO
private val scope = CoroutineScope(Dispatchers.Default)
private val readerFlow: SharedFlow<ReaderResult2> = TODO()
    .provideReplayCacheInvalidationManager<ReaderResult2>(copyDownstream = Invalidation.Optional)
    .sharePauseableIn(scope, WhileSubscribed(), replay = 1)
val songFlow: Flow<IncrementalList<MediaItem>> = readerFlow.map { it.songList }

private val allowedFoldersForCoversFlow: SharedFlow<Set<String>> = songFlow
    .groupByIncremental { it.getFile()?.absolutePath }
    .filterIncremental { folder, songs ->
        if (folder != null) {
            val firstAlbum = songs.after.first().mediaMetadata.albumId
            songs.after.find { it.mediaMetadata.albumId != firstAlbum } == null
        } else false
    }
    .map { @Suppress("UNCHECKED_CAST") (it.after.keys as Set<String>) }
    .provideReplayCacheInvalidationManager(copyDownstream = Invalidation.Optional)
    .sharePauseableIn(scope, WhileSubscribed(), replay = 1)

private val rawAlbumsFlow: Flow<IncrementalMap<Long?, IncrementalList<MediaItem>>> = songFlow
    .groupByIncremental { it.mediaMetadata.albumId }
    .provideReplayCacheInvalidationManager(copyDownstream = Invalidation.Required)
    .sharePauseableIn(scope, WhileSubscribed(), replay = 1)
val albumsFlow: SharedFlow<IncrementalList<Album2>> = rawAlbumsFlow
    .mapIncremental { albumId, songs ->
        val songList = songs.after
        val title = songList.first().mediaMetadata.albumTitle?.toString()
        val year = songList.mapNotNull { it.mediaMetadata.releaseYear }.maxOrNull()
        val artist = MiscUtils.findBestAlbumArtist(songList)
        val songCount = songList.size
        val fallbackCover = songList.first().mediaMetadata.artworkUri
        val albumArtFlow = if (useEnhancedCoverReading) {
            val firstFolder = songList.first().getFile()?.parent
            val eligibleForFolderAlbumArt = firstFolder != null && albumId != null &&
                    songList.find { it.getFile()?.parent != firstFolder } == null
            if (!eligibleForFolderAlbumArt) flowOf(fallbackCover)
            else allowedFoldersForCoversFlow.map { it.contains(firstFolder) }.distinctUntilChanged().map {
                if (it) {
                    if (coverStubUri != null)
                        Uri.Builder().scheme(coverStubUri)
                            .authority(albumId.toString()).path(firstFolder).build()
                    else
                        findBestCover(File(firstFolder))?.toUriCompat()
                } else fallbackCover
            }
        } else flowOf(
            if (albumId != null)
                ContentUris.withAppendedId(Constants.baseAlbumCoverUri, albumId) else fallbackCover
        )
        val artistIdFlow = if (artist?.second != null) flowOf(artist.second) else if (artist != null)
            readerFlow.map { it.canonicalArtistIdMap[artist.first] }.distinctUntilChanged() else flowOf(null)
        albumArtFlow.combine(artistIdFlow) { cover, artistId ->
            Album2(albumId, title, artist?.first, artistId, year, cover, songCount)
        }
    }
    .flattenIncremental()
    .toIncrementalList(::compareValues)
    .provideReplayCacheInvalidationManager(copyDownstream = Invalidation.Optional)
    .sharePauseableIn(scope, WhileSubscribed(), replay = 1)

fun getSongsInAlbum(album: Album2): Flow<IncrementalList<MediaItem>?> = rawAlbumsFlow
    .forKey(album.id)

private val albumsForArtistFlow: Flow<IncrementalMap<Long?, IncrementalList<Album2>>> = albumsFlow
    .groupByIncremental { it.albumArtistId }
    .provideReplayCacheInvalidationManager(copyDownstream = Invalidation.Optional)
    .sharePauseableIn(scope, WhileSubscribed(), replay = 1)
fun getAlbumsForArtist(artist: Artist2): Flow<IncrementalList<Album2>?> = albumsForArtistFlow.forKey(artist.id)

private val rawArtistFlow: Flow<IncrementalMap<Long?, IncrementalList<MediaItem>>> = songFlow
    .groupByIncremental { it.mediaMetadata.artistId }
    .provideReplayCacheInvalidationManager(copyDownstream = Invalidation.Optional)
    .sharePauseableIn(scope, WhileSubscribed(), replay = 1)
private val artistsWithoutSongsFlow = albumsForArtistFlow
    .filterLatestIncremental { artistId, albums ->
        rawArtistFlow.forKey(artistId).map { it == null }
    }
    .mapIncremental { artistId, albums ->
        val firstAlbum = albums.after.first() // TODO is this unsorted? non-deterministic?!
        flowOf(Artist2(artistId, firstAlbum.albumArtist, firstAlbum.cover, 0, albums.after.size))
    }
    .flattenIncremental()
val artistFlow: SharedFlow<IncrementalList<Artist2>> = rawArtistFlow
    .mapIncremental { artistId, songs ->
        val songList = songs.after
        val title = songList.first().mediaMetadata.artist?.toString()
        val cover = songList.first().mediaMetadata.artworkUri
        val songCount = songList.size
        albumsForArtistFlow
            .forKey(artistId)
            .map { it?.after?.size ?: 0 }
            .distinctUntilChanged()
            .map { albumCount ->
                Artist2(artistId, title, cover, songCount, albumCount)
            }
    }
    .flattenIncremental()
    .mergeWithIncremental(artistsWithoutSongsFlow)
    .toIncrementalList(::compareValues)
    .provideReplayCacheInvalidationManager()
    .sharePauseableIn(scope, WhileSubscribed(), replay = 1)

fun getSongsForArtist(artist: Artist2): Flow<IncrementalList<MediaItem>?> = rawArtistFlow.forKey(artist.id)

// TODO make proper album artists (songs sorted by album artist) tab again

// TODO dates

// TODO genres

// TODO folder flat tree

// TODO filesystem tree

// TODO id map

// TODO playlists