package com.jonesmb.pulasmartagent.domain.model

import kotlinx.datetime.Instant

/** A single GPS reading taken by the device. */
data class GpsCoordinate(
    val latitude: Double,
    val longitude: Double,
    val accuracyMetres: Float,
    val capturedAt: Instant,
)