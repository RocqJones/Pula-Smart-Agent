package com.jonesmb.pulasmartagent.domain.gps

import com.jonesmb.pulasmartagent.core.constants.Constants
import com.jonesmb.pulasmartagent.core.util.haversineMetres
import com.jonesmb.pulasmartagent.domain.model.FieldBoundary
import com.jonesmb.pulasmartagent.domain.model.GpsCoordinate
import kotlinx.datetime.Clock

/** Drops readings whose self-reported error radius exceeds [maxAccuracyMetres]. */
object AccuracyGate {
    fun accept(
        coordinate: GpsCoordinate,
        maxAccuracyMetres: Float = Constants.Gps.MAX_ACCURACY_METRES,
    ): Boolean = coordinate.accuracyMetres <= maxAccuracyMetres
}

/**
 * Collects GPS pings and returns a stable centroid once [windowSize] consecutive pings
 * all land within [clusterRadiusMetres] of each other. Resets on confirmation.
 */
class StabilityBuffer(
    private val windowSize: Int = Constants.Gps.STABILITY_WINDOW_SIZE,
    private val clusterRadiusMetres: Double = Constants.Gps.CLUSTER_RADIUS_METRES,
) {
    private val buffer = mutableListOf<GpsCoordinate>()

    fun feed(coordinate: GpsCoordinate): GpsCoordinate? {
        buffer.add(coordinate)
        if (buffer.size > windowSize) buffer.removeAt(0)
        if (buffer.size < windowSize) return null

        val centroidLat = buffer.map { it.latitude }.average()
        val centroidLng = buffer.map { it.longitude }.average()

        val allWithinRadius = buffer.all { point ->
            haversineMetres(
                centroidLat,
                centroidLng,
                point.latitude,
                point.longitude
            ) <= clusterRadiusMetres
        }

        return if (allWithinRadius) {
            GpsCoordinate(
                latitude = centroidLat,
                longitude = centroidLng,
                accuracyMetres = buffer.map { it.accuracyMetres }.average().toFloat(),
                capturedAt = buffer.last().capturedAt,
            ).also { buffer.clear() }
        } else null
    }

    fun reset() = buffer.clear()
}

/** Validates a finished polygon before it is persisted. */
object PolygonValidator {

    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }

    fun validate(corners: List<GpsCoordinate>): ValidationResult {
        if (corners.size < 3)
            return ValidationResult.Invalid("At least 3 corner points are required (got ${corners.size})")
        if (hasSelfIntersection(corners))
            return ValidationResult.Invalid("Boundary sides cross each other — re-walk the perimeter")
        if (signedArea(corners) == 0.0)
            return ValidationResult.Invalid("All points are on the same line — no enclosed area")
        return ValidationResult.Valid
    }

    /** Shoelace formula — returns 0 for collinear points. */
    internal fun signedArea(corners: List<GpsCoordinate>): Double {
        val n = corners.size
        var area = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += corners[i].longitude * corners[j].latitude
            area -= corners[j].longitude * corners[i].latitude
        }
        return area / 2.0
    }

    private fun hasSelfIntersection(corners: List<GpsCoordinate>): Boolean {
        val n = corners.size
        for (i in 0 until n) {
            val a1 = corners[i];
            val a2 = corners[(i + 1) % n]
            for (j in i + 2 until n) {
                if (j == n - 1 && i == 0) continue
                val b1 = corners[j];
                val b2 = corners[(j + 1) % n]
                if (segmentsIntersect(a1, a2, b1, b2)) return true
            }
        }
        return false
    }

    private fun segmentsIntersect(
        p1: GpsCoordinate, p2: GpsCoordinate,
        p3: GpsCoordinate, p4: GpsCoordinate,
    ): Boolean {
        val d1 = direction(p3, p4, p1);
        val d2 = direction(p3, p4, p2)
        val d3 = direction(p1, p2, p3);
        val d4 = direction(p1, p2, p4)
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
                ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
    }

    private fun direction(a: GpsCoordinate, b: GpsCoordinate, c: GpsCoordinate): Double =
        (c.longitude - a.longitude) * (b.latitude - a.latitude) -
                (b.longitude - a.longitude) * (c.latitude - a.latitude)
}

/**
 * Orchestrates AccuracyGate → StabilityBuffer → PolygonValidator.
 * Call [feed] for each raw GPS ping; call [finaliseBoundary] when the agent closes the shape.
 */
class GpsBoundaryCapture(
    private val accuracyGate: AccuracyGate = AccuracyGate,
    private val stabilityBuffer: StabilityBuffer = StabilityBuffer(),
) {
    private val confirmedCorners = mutableListOf<GpsCoordinate>()

    /** Returns confirmed corner when the buffer is satisfied, null while still collecting. */
    fun feed(raw: GpsCoordinate): GpsCoordinate? {
        if (!accuracyGate.accept(raw)) return null
        val stable = stabilityBuffer.feed(raw) ?: return null
        confirmedCorners.add(stable)
        return stable
    }

    fun confirmedCornerCount(): Int = confirmedCorners.size

    /** Throws [IllegalStateException] with the validation reason if the boundary is invalid. */
    fun finaliseBoundary(fieldId: String, farmerId: String): FieldBoundary {
        val result = PolygonValidator.validate(confirmedCorners)
        check(result is PolygonValidator.ValidationResult.Valid) {
            (result as PolygonValidator.ValidationResult.Invalid).reason
        }
        return FieldBoundary(
            fieldId = fieldId,
            farmerId = farmerId,
            corners = confirmedCorners.toList(),
            capturedAt = Clock.System.now(),
            meanAccuracyMetres = confirmedCorners.map { it.accuracyMetres }.average().toFloat(),
        )
    }

    fun reset() {
        confirmedCorners.clear()
        stabilityBuffer.reset()
    }
}
