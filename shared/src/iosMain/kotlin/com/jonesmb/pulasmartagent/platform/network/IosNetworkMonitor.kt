package com.jonesmb.pulasmartagent.platform.network

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_get_global_queue
import kotlin.coroutines.resume

class IosNetworkMonitor : NetworkMonitor {

    override suspend fun isConnected(): Boolean = suspendCancellableCoroutine { cont ->
        val monitor = nw_path_monitor_create()
        nw_path_monitor_set_update_handler(monitor) { path ->
            nw_path_monitor_cancel(monitor)
            cont.resume(nw_path_get_status(path) == nw_path_status_satisfied)
        }
        nw_path_monitor_set_queue(
            monitor,
            dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)
        )
        nw_path_monitor_start(monitor)
        cont.invokeOnCancellation { nw_path_monitor_cancel(monitor) }
    }
}