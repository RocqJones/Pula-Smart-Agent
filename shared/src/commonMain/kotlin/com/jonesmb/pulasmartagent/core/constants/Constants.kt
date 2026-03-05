package com.jonesmb.pulasmartagent.core.constants

object Constants {
    const val NODE_TYPE_ANSWER = "ANSWER"
    const val NODE_TYPE_REPEATING_SECTION = "REPEATING_SECTION"

    object Gps {
        const val MAX_ACCURACY_METRES = 15f
        const val STABILITY_WINDOW_SIZE = 5
        const val CLUSTER_RADIUS_METRES = 5.0
    }
}