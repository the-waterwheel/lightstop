package com.lightmeter.rawmeter

import android.view.MotionEvent
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the hidden tool before its first draw, as after an application restart. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlashExposureRestorationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val saved = FlashConfiguration(guideNumber = 40.0, guideNumberReferenceIso = 200,
        iso = 320, powerDenominator = 4, lossStops = 0.75, distanceMeters = 3.0)

    @Test fun firstDialUpdateKeepsRestoredFlashApplied() {
        FlashExposureRepository(context).apply(saved)
        val repository = FlashExposureRepository(context)
        val state = MeterState(context)
        state.setAppliedFlashConfiguration(repository.applied(state.iso))
        val view = FlashExposureView(context, state, repository)
        view.listener = object : ListenerStub() {
            override fun onAppliedFlashChanged(configuration: FlashConfiguration?) {
                state.setAppliedFlashConfiguration(configuration)
            }
        }
        val changed = saved.copy(distanceMeters = 4.0)
        view.updateConfiguration(changed)
        assertEquals(changed, state.appliedFlashConfiguration)
        assertEquals(changed, FlashExposureRepository(context).applied(state.iso))
    }

    @Test fun hiddenDistanceUpdatePersistsAnAppliedAutomaticConfiguration() {
        FlashExposureRepository(context).apply(saved.copy(distanceMeters = null))
        val state = MeterState(context)
        val view = FlashExposureView(context, state, FlashExposureRepository(context))
        view.updateDistance(8.0)
        assertEquals(saved.copy(distanceMeters = 8.0), FlashExposureRepository(context).applied(state.iso))
    }

    @Test fun editingAnUnappliedConfigurationDoesNotApplyIt() {
        val repository = FlashExposureRepository(context)
        repository.saveSelected(saved)
        val view = FlashExposureView(context, MeterState(context), repository)
        var notified = false
        view.listener = object : ListenerStub() {
            override fun onAppliedFlashChanged(configuration: FlashConfiguration?) {
                assertNull(configuration)
                notified = true
            }
        }
        view.updateConfiguration(saved.copy(distanceMeters = 5.0))
        assertTrue(notified)
        assertNull(repository.applied(100))
    }

    @Test fun explicitRemovalRemainsRemovedWhenDistanceChanges() {
        val repository = FlashExposureRepository(context)
        repository.apply(saved)
        val view = FlashExposureView(context, MeterState(context), repository)
        view.openPage()
        view.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 720, 720)
        val button = ReciprocityGeometryCalculator.calculate(720, 720,
            context.resources.displayMetrics.density).apply
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(0, 10, action, button.centerX(), button.centerY(), 0).also {
                view.onTouchEvent(it)
                it.recycle()
            }
        }
        assertNull(repository.applied(100))
        view.updateConfiguration(saved.copy(distanceMeters = 5.0))
        assertNull(repository.applied(100))
    }

    private open class ListenerStub : FlashExposureView.Listener {
        override fun onBackToToolsRequested() = Unit
        override fun onCloseRequested() = Unit
        override fun onSettingsRequested(configuration: FlashConfiguration) = Unit
        override fun onGuideNumberRequested(configuration: FlashConfiguration) = Unit
        override fun onMeteringIsoRequested(configuration: FlashConfiguration) = Unit
        override fun onAppliedFlashChanged(configuration: FlashConfiguration?) = Unit
        override fun onDistanceSelectionChanged(automatic: Boolean) = Unit
    }
}
