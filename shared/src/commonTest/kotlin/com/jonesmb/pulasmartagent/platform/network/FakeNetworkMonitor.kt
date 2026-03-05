package com.jonesmb.pulasmartagent.platform.network

class FakeNetworkMonitor(private var connected: Boolean = true) : NetworkMonitor {
    override suspend fun isConnected(): Boolean = connected
    fun setConnected(value: Boolean) { connected = value }
}
