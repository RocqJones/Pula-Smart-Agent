package com.jonesmb.pulasmartagent.platform.network

interface NetworkMonitor {
    suspend fun isConnected(): Boolean
}
