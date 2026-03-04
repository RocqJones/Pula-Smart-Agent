package com.jonesmb.pulasmartagent

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform