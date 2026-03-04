package com.jonesmb.pulasmartagent.platform.network

class FakeNetworkMonitor(initiallyConnected: Boolean = true) : NetworkMonitor {
    override var isConnected: Boolean = initiallyConnected
}

