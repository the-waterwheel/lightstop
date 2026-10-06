package com.lightmeter.rawmeter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w360dp-h720dp-xhdpi")
class ParameterHistoryDrawerTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun draggingAndCancellingDrawerDoesNotMutateCapturedExposure() {
        val repository = ParameterRecordRepository(context)
        RecordPreviewFixtures.seed(repository)
        val before = repository.categories()
        val view = RecordPreviewFixtures.history(context, MeterState(context), repository, 720, 1440, "history-detail")
        val closed = geometry(0f)
        val open = geometry(1f)
        assertDrawerAction(view, AccessibilityNodeInfo.ACTION_EXPAND)
        drag(view, closed.meteringHandle.centerX(), closed.meteringHandle.centerY(),
            closed.meteringHandle.centerY() - open.metering.height() * 0.7f, false)
        assertDrawerAction(view, AccessibilityNodeInfo.ACTION_COLLAPSE)
        drag(view, open.meteringHandle.centerX(), open.meteringHandle.centerY(),
            open.meteringHandle.centerY() + open.metering.height() * 0.7f, true)
        assertDrawerAction(view, AccessibilityNodeInfo.ACTION_COLLAPSE)
        drag(view, open.meteringHandle.centerX(), open.meteringHandle.centerY(),
            open.meteringHandle.centerY() + open.metering.height() * 0.7f, false)
        assertDrawerAction(view, AccessibilityNodeInfo.ACTION_EXPAND)
        assertEquals(before, repository.categories())
    }

    @Test fun hiddenScalesCannotChangePlaybackAndCanBeExpandedByAccessibility() {
        val repository = ParameterRecordRepository(context)
        RecordPreviewFixtures.seed(repository)
        val view = RecordPreviewFixtures.history(context, MeterState(context), repository, 720, 1440, "history-detail")
        val closed = geometry(0f)
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN,
            closed.metering.centerX(), closed.metering.centerY(), 0)
        assertFalse(view.onTouchEvent(event))
        event.recycle()
        assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_EXPAND, null))
        RecordPreviewFixtures.settle()
        assertDrawerAction(view, AccessibilityNodeInfo.ACTION_COLLAPSE)
        assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_COLLAPSE, null))
        RecordPreviewFixtures.settle()
        assertDrawerAction(view, AccessibilityNodeInfo.ACTION_EXPAND)
    }

    @Test fun drawerBoundsRemainUsableAcrossWindowSizes() {
        for ((w, h) in listOf(720 to 1440, 1800 to 840, 480 to 600)) {
            for (fraction in listOf(0f, 0.3f, 1f)) {
                val geometry = ParameterHistoryGeometryCalculator.calculate(w, h, 2f, 0f, fraction)
                assertTrue(geometry.meteringDrawer.top >= geometry.data.top)
                assertTrue(geometry.meteringDrawer.bottom <= h)
                assertEquals(geometry.data.bottom, geometry.meteringDrawer.bottom, 0f)
                assertEquals(geometry.meteringHandle.bottom, geometry.metering.top, 0f)
                if (fraction == 1f) assertEquals(geometry.meteringDrawer.bottom, geometry.metering.bottom, 0.01f)
            }
        }
    }

    @Test fun editedPlaybackSurvivesCollapseAndReopeningWithoutChangingTheRecord() {
        val repository = ParameterRecordRepository(context)
        RecordPreviewFixtures.seed(repository)
        val before = repository.categories()
        val view = RecordPreviewFixtures.history(context, MeterState(context), repository, 720, 1440, "history-detail-expanded")
        val originalSummary = summary(view)
        val rect = geometry(1f).metering
        val x = rect.centerX()
        val y = rect.bottom - 26f * 2f
        for ((action, px) in listOf(MotionEvent.ACTION_DOWN to x, MotionEvent.ACTION_MOVE to x - 120f,
            MotionEvent.ACTION_UP to x - 120f)) {
            MotionEvent.obtain(0, 20, action, px, y, 0).also { view.onTouchEvent(it); it.recycle() }
        }
        RecordPreviewFixtures.settle()
        val editedSummary = summary(view)
        assertNotEquals(originalSummary, editedSummary)
        view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_COLLAPSE, null)
        RecordPreviewFixtures.settle()
        view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_EXPAND, null)
        RecordPreviewFixtures.settle()
        assertEquals(editedSummary, summary(view))
        assertEquals(before, repository.categories())
    }

    private fun geometry(fraction: Float) = ParameterHistoryGeometryCalculator.calculate(720, 1440, 2f, 0f, fraction)

    @Suppress("DEPRECATION")
    private fun summary(view: ParameterHistoryView): String {
        val info = AccessibilityNodeInfo.obtain()
        view.onInitializeAccessibilityNodeInfo(info)
        return info.contentDescription.toString().also { info.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun assertDrawerAction(view: ParameterHistoryView, action: Int) {
        // Rendering clamps the same metadata viewport used by the interactive detail page.
        val bitmap = Bitmap.createBitmap(720, 1440, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        bitmap.recycle()
        val info = AccessibilityNodeInfo.obtain()
        view.onInitializeAccessibilityNodeInfo(info)
        assertTrue(info.actionList.any { it.id == action })
        info.recycle()
    }

    private fun drag(view: ParameterHistoryView, x: Float, start: Float, end: Float, cancel: Boolean) {
        for ((action, y) in listOf(MotionEvent.ACTION_DOWN to start, MotionEvent.ACTION_MOVE to end,
            (if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP) to end)) {
            MotionEvent.obtain(0, 20, action, x, y, 0).also { view.onTouchEvent(it); it.recycle() }
        }
        RecordPreviewFixtures.settle()
    }

}
