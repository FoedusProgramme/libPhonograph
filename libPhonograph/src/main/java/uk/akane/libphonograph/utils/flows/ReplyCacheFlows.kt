package uk.akane.libphonograph.utils.flows

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

enum class Invalidation {
    Required,
    Optional,
    Never
}

class ReplayCacheInvalidationManager(val invalidate: () -> Unit) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = Key
    companion object Key : CoroutineContext.Key<ReplayCacheInvalidationManager>
}

suspend fun requireReplayCacheInvalidationManager() =
    currentCoroutineContext()[ReplayCacheInvalidationManager]
        ?: throw IllegalStateException("Replay cache invalidation not available here")

@OptIn(ExperimentalCoroutinesApi::class)
fun <T> Flow<T>.provideReplayCacheInvalidationManager(copyDownstream: Invalidation = Invalidation.Never) = object : Flow<T> {
    override suspend fun collect(collector: FlowCollector<T>) {
        val sharedFlow = collector as? MutableSharedFlow<T>
            ?: throw IllegalStateException("withReplayCacheInvalidation needs to be used _directly_ before shareIn")
        if (sharedFlow is MutableStateFlow<T>)
            throw IllegalStateException("withReplayCacheInvalidation does not support state flows")
        val downstream = if (copyDownstream != Invalidation.Never)
            currentCoroutineContext()[ReplayCacheInvalidationManager] else null
        if (downstream == null && copyDownstream == Invalidation.Required)
            throw IllegalStateException("Replay cache invalidation not available but copyDownstream is Required")
        withContext(ReplayCacheInvalidationManager(if (downstream != null) object : Function0<Unit> {
            override fun invoke() {
                sharedFlow.resetReplayCache()
                downstream.invalidate()
            }
        } else sharedFlow::resetReplayCache)) {
            return@withContext this@provideReplayCacheInvalidationManager.collect(collector)
        }
    }
}