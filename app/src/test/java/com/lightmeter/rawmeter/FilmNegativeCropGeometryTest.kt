package com.lightmeter.rawmeter

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class FilmNegativeCropGeometryTest {
    private val crop = FilmNegativeCrop(300f, 400f, 200f, 120f, .4f)
    private fun same(a: FilmNegativePoint, b: FilmNegativePoint) {
        assertEquals(a.x, b.x, .002f); assertEquals(a.y, b.y, .002f)
    }

    @Test fun rotatedPortraitCropRoundTripsWithoutAspectDistortion() {
        val r = crop.region(600, 900)
        val restored = FilmNegativeCrop.from(r, 600, 900)
        assertEquals(crop.width, restored.width, .001f)
        assertEquals(crop.height, restored.height, .001f)
        assertEquals(crop.angle, restored.angle, .001f)
        crop.corners().zip(restored.corners()).forEach { (a, b) -> same(a, b) }
    }

    @Test fun eachEdgeMovesPerpendicularlyAndKeepsOppositeEdgeFixed() {
        for (index in 0..3) {
            val handle = FilmNegativeCropHandle.edges[index]
            val dx = crop.point(handle.sideX * 20f, handle.sideY * 20f)
            val resized = crop.resize(dx.x - crop.centerX, dx.y - crop.centerY,
                handle.sideX, handle.sideY, 12f)
            val opposite = (index + 2) % 4
            same(crop.corners()[opposite], resized.corners()[opposite])
            same(crop.corners()[(opposite + 1) % 4], resized.corners()[(opposite + 1) % 4])
            assertEquals(crop.angle, resized.angle, 0f)
            assertEquals(crop.width + if (handle.sideX != 0) 20 else 0, resized.width, .001f)
            assertEquals(crop.height + if (handle.sideY != 0) 20 else 0, resized.height, .001f)
        }
    }

    @Test fun allCornersResizeBothDimensionsAroundTheirOppositeAnchor() {
        for (index in 0..3) {
            val handle = FilmNegativeCropHandle.corners[index]
            val target = crop.point(handle.sideX * 30f, handle.sideY * 15f)
            val resized = crop.resize(target.x - crop.centerX, target.y - crop.centerY,
                handle.sideX, handle.sideY, 12f)
            same(crop.corners()[(index + 2) % 4], resized.corners()[(index + 2) % 4])
            assertEquals(230f, resized.width, .001f); assertEquals(135f, resized.height, .001f)
        }
    }

    @Test fun draggedCornerCannotCrossOrInvertTheFrame() {
        val resized = crop.resize(-2000f, -2000f, 1, 1, 12f)
        assertTrue(resized.width >= 12f && resized.height >= 12f)
        same(crop.corners()[0], resized.corners()[0])
        assertTrue(resized.region(600, 900).validCorners())
    }

    @Test fun boundaryClampingKeepsTheResizeAnchorAndAllCornersInImage() {
        val resized = crop.bounded(crop.resize(2000f, 3000f, 1, 1, 12f), 600f, 900f, 12f)
        assertTrue(resized.fits(600f, 900f, 12f))
        same(crop.corners()[0], resized.corners()[0])
        assertTrue(resized.width > crop.width)
    }

    @Test fun movingARotatedCropClampsItsCornersAndPreservesSize() {
        val moved = crop.move(-2000f, 3000f, 600f, 900f)
        assertTrue(moved.fits(600f, 900f, 12f))
        assertEquals(crop.width, moved.width, 0f); assertEquals(crop.height, moved.height, 0f)
        assertEquals(crop.angle, moved.angle, 0f)
    }

    @Test fun rotationKeepsItsCenterAndStopsBeforeImageBoundary() {
        val start = FilmNegativeCrop(90f, 70f, 160f, 100f)
        val rotated = start.bounded(start.copy(angle = (PI / 2).toFloat()), 180f, 140f, 12f)
        assertTrue(rotated.fits(180f, 140f, 12f))
        assertTrue(rotated.angle > 0f && rotated.angle < PI / 2)
        assertEquals(start.centerX, rotated.centerX, 0f); assertEquals(start.width, rotated.width, 0f)
    }

    @Test fun cornerAndEdgeHitTargetsWorkAfterRotation() {
        for (i in 0..3) {
            val p = crop.corners()[i]
            assertEquals(FilmNegativeCropHandle.corners[i], crop.hit(p.x, p.y, 10f))
            val q = crop.corners()[(i + 1) % 4]
            assertEquals(FilmNegativeCropHandle.edges[i], crop.hit((p.x + q.x) / 2, (p.y + q.y) / 2, 10f))
        }
        assertEquals(FilmNegativeCropHandle.MOVE, crop.hit(crop.centerX, crop.centerY, 500f))
        val outside = crop.point(0f, -crop.height)
        assertEquals(FilmNegativeCropHandle.ROTATE, crop.hit(outside.x, outside.y, 10f))
    }

    @Test fun rotationAcrossTheAngleSeamDoesNotJumpByAFullTurn() {
        val delta = FilmNegativeCrop.angleDelta((PI - .05).toFloat(), (-PI + .05).toFloat())
        assertEquals(.1f, delta, 1e-5f)
        assertTrue(abs(FilmNegativeCrop.angleDelta(0f, 10f)) <= PI)
    }

    @Test fun rotatedRegionRejectsTheCornersOfItsBoundingBox() {
        val r = FilmNegativeCrop(100f, 100f, 100f, 50f, (PI / 4).toFloat()).region(200, 200)
        assertTrue(r.validCorners()); assertTrue(r.contains(.5f, .5f))
        assertFalse(r.contains(r.left + .001f, r.top + .001f))
        assertFalse(r.contains(.99f, .99f))
    }

    @Test fun invalidSelfIntersectingOrNonFinitePolygonsAreRejected() {
        val p = listOf(FilmNegativePoint(.1f, .1f), FilmNegativePoint(.9f, .9f),
            FilmNegativePoint(.9f, .1f), FilmNegativePoint(.1f, .9f))
        val r = FilmNegativeRegion(.1f, .1f, .9f, .9f, p)
        assertFalse(r.validCorners())
        assertFalse(r.copy(corners = p.take(3)).validCorners())
        assertFalse(r.copy(corners = p.map { it.copy(x = Float.NaN) }).validCorners())
    }
}
