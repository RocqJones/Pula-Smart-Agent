package com.jonesmb.pulasmartagent.domain.gps

import com.jonesmb.pulasmartagent.core.constants.Constants
import com.jonesmb.pulasmartagent.core.util.haversineMetres
import com.jonesmb.pulasmartagent.domain.model.GpsCoordinate
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpsBoundaryCaptureTest {

    // Helpers
    private fun coord(
        lat: Double = -1.2921,
        lng: Double = 36.8219,
        accuracy: Float = 5f,
        ms: Long = 0L,
    ) = GpsCoordinate(lat, lng, accuracy, Instant.fromEpochMilliseconds(ms))

    /** Feed the same coordinate [windowSize] times to satisfy StabilityBuffer. */
    private fun fillBuffer(
        capture: GpsBoundaryCapture,
        lat: Double,
        lng: Double,
        accuracy: Float = 5f,
        windowSize: Int = 5,
    ): GpsCoordinate? {
        var result: GpsCoordinate? = null
        repeat(windowSize) { i ->
            result = capture.feed(coord(lat, lng, accuracy, ms = i.toLong()))
        }
        return result
    }

   
    // AccuracyGate
    @Test
    fun `AccuracyGate accepts reading within threshold`() {
        assertTrue(AccuracyGate.accept(coord(accuracy = 14.9f)))
    }

    @Test
    fun `AccuracyGate accepts reading exactly at threshold`() {
        assertTrue(AccuracyGate.accept(coord(accuracy = 15f)))
    }

    @Test
    fun `AccuracyGate rejects reading above threshold`() {
        val noisy = coord(accuracy = 16f)
        assertTrue(!AccuracyGate.accept(noisy))
    }

    @Test
    fun `AccuracyGate noisy reading is not forwarded to StabilityBuffer`() {
        val capture = GpsBoundaryCapture()
        // 5 noisy pings — buffer should never confirm a corner
        repeat(5) { capture.feed(coord(accuracy = 20f)) }
        assertEquals(0, capture.confirmedCornerCount())
    }

   
    // StabilityBuffer
   

    @Test
    fun `StabilityBuffer returns null until window is full`() {
        val buffer = StabilityBuffer(windowSize = 5)
        repeat(4) { i ->
            assertNull(buffer.feed(coord(ms = i.toLong())))
        }
    }

    @Test
    fun `StabilityBuffer confirms corner when all pings cluster within radius`() {
        val buffer = StabilityBuffer(windowSize = 5, clusterRadiusMetres = 5.0)
        // All at the same point — guaranteed to cluster
        var corner: GpsCoordinate? = null
        repeat(5) { i -> corner = buffer.feed(coord(lat = -1.2921, lng = 36.8219, ms = i.toLong())) }
        assertNotNull(corner)
    }

    @Test
    fun `StabilityBuffer returns null when pings are too spread out`() {
        val buffer = StabilityBuffer(windowSize = 5, clusterRadiusMetres = 5.0)
        // Alternate between two points ~50 m apart
        val coords = listOf(
            coord(lat = -1.2921, lng = 36.8219),
            coord(lat = -1.2926, lng = 36.8219), // ~55 m south
            coord(lat = -1.2921, lng = 36.8219),
            coord(lat = -1.2926, lng = 36.8219),
            coord(lat = -1.2921, lng = 36.8219),
        )
        val last = coords.map { buffer.feed(it) }.last()
        assertNull(last)
    }

    @Test
    fun `StabilityBuffer centroid is average of window coordinates`() {
        val buffer = StabilityBuffer(windowSize = 3, clusterRadiusMetres = 5.0)
        // Three identical points — centroid should equal the point
        val lat = -1.2921
        val lng = 36.8219
        var corner: GpsCoordinate? = null
        repeat(3) { i -> corner = buffer.feed(coord(lat = lat, lng = lng, ms = i.toLong())) }
        assertNotNull(corner)
        assertEquals(lat, corner.latitude, absoluteTolerance = 0.00001)
        assertEquals(lng, corner.longitude, absoluteTolerance = 0.00001)
    }

   
    // PolygonValidator
    @Test
    fun `PolygonValidator rejects fewer than 3 corners`() {
        val result = PolygonValidator.validate(listOf(coord(), coord(lat = -1.293)))
        assertTrue(result is PolygonValidator.ValidationResult.Invalid)
    }

    @Test
    fun `PolygonValidator accepts a valid triangle`() {
        val triangle = listOf(
            coord(lat = 0.0, lng = 0.0),
            coord(lat = 1.0, lng = 0.0),
            coord(lat = 0.5, lng = 1.0),
        )
        assertEquals(PolygonValidator.ValidationResult.Valid, PolygonValidator.validate(triangle))
    }

    @Test
    fun `PolygonValidator rejects collinear points`() {
        val line = listOf(
            coord(lat = 0.0, lng = 0.0),
            coord(lat = 1.0, lng = 1.0),
            coord(lat = 2.0, lng = 2.0),
        )
        val result = PolygonValidator.validate(line)
        assertTrue(result is PolygonValidator.ValidationResult.Invalid)
    }

    @Test
    fun `PolygonValidator rejects self-intersecting boundary`() {
        // Figure-8 shape
        val bowtie = listOf(
            coord(lat = 0.0, lng = 0.0),
            coord(lat = 1.0, lng = 1.0),
            coord(lat = 1.0, lng = 0.0),
            coord(lat = 0.0, lng = 1.0),
        )
        val result = PolygonValidator.validate(bowtie)
        assertTrue(result is PolygonValidator.ValidationResult.Invalid)
    }

   
    // GpsBoundaryCapture end-to-end
    @Test
    fun `full capture flow produces valid FieldBoundary with 3 corners`() {
        val capture = GpsBoundaryCapture()

        // Three well-separated corner points forming a triangle
        val corners = listOf(
            Pair(-1.2921, 36.8219),
            Pair(-1.2931, 36.8219),
            Pair(-1.2926, 36.8230),
        )

        corners.forEach { (lat, lng) ->
            val confirmed = fillBuffer(capture, lat, lng)
            assertNotNull(confirmed) { "Expected corner to be confirmed for ($lat, $lng)" }
        }

        assertEquals(3, capture.confirmedCornerCount())

        val boundary = capture.finaliseBoundary("field-001", "farmer-001")
        assertEquals(3, boundary.corners.size)
        assertEquals("field-001", boundary.fieldId)
        assertTrue(boundary.meanAccuracyMetres <= Constants.Gps.MAX_ACCURACY_METRES)
    }

    @Test
    fun `finaliseBoundary throws when fewer than 3 corners confirmed`() {
        val capture = GpsBoundaryCapture()
        fillBuffer(capture, lat = -1.2921, lng = 36.8219)
        fillBuffer(capture, lat = -1.2931, lng = 36.8219)
        // Only 2 corners — should fail validation
        assertFailsWith<IllegalStateException> {
            capture.finaliseBoundary("field-002", "farmer-001")
        }
    }

    @Test
    fun `noisy pings do not contribute to confirmed corners`() {
        val capture = GpsBoundaryCapture()
        repeat(10) { capture.feed(coord(accuracy = 30f)) } // all noisy
        assertEquals(0, capture.confirmedCornerCount())
    }

    @Test
    fun `meanAccuracyMetres is correctly averaged across corners`() {
        val capture = GpsBoundaryCapture(stabilityBuffer = StabilityBuffer(windowSize = 1))
        // Window of 1 — each single ping becomes a confirmed corner immediately
        val cornersData = listOf(
            Triple(-1.2921, 36.8219, 4f),
            Triple(-1.2931, 36.8219, 6f),
            Triple(-1.2926, 36.8230, 8f),
        )
        cornersData.forEach { (lat, lng, acc) ->
            capture.feed(coord(lat, lng, acc))
        }
        val boundary = capture.finaliseBoundary("field-003", "farmer-001")
        assertEquals(6f, boundary.meanAccuracyMetres, absoluteTolerance = 0.01f)
    }

   
    // Haversine distance
    @Test
    fun `haversineMetres returns ~0 for identical points`() {
        val d = haversineMetres(-1.2921, 36.8219, -1.2921, 36.8219)
        assertTrue(d < 0.01)
    }

    @Test
    fun `haversineMetres returns reasonable distance between two Nairobi points`() {
        // ~556 m north
        val d = haversineMetres(-1.2921, 36.8219, -1.2871, 36.8219)
        assertTrue(d in 500.0..620.0, "Expected ~556 m, got $d m")
    }
}