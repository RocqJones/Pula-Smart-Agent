package com.jonesmb.pulasmartagent.platform.flow

import com.jonesmb.pulasmartagent.domain.model.SyncProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Collects a StateFlow from Swift by launching a coroutine and forwarding emissions to a callback.
object FlowCollectorHelper {
    fun collect(
        flow: StateFlow<SyncProgress?>,
        onEach: (SyncProgress?) -> Unit,
    ): Job {
        val scope = CoroutineScope(Dispatchers.Main)
        return scope.launch {
            flow.collect { onEach(it) }
        }
    }
}

