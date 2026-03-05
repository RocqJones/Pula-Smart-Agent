package com.jonesmb.pulasmartagent.domain.model

import kotlinx.datetime.Instant

/** A validated field boundary saved after the agent walks the perimeter. */
data class FieldBoundary(
    val fieldId: String,
    val farmerId: String,
    val corners: List<GpsCoordinate>,
    val capturedAt: Instant,
    val meanAccuracyMetres: Float,
)