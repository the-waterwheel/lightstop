package com.lightmeter.rawmeter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/** Production settings Canvas, hit targets and entry routing, with no camera or tracker algorithm. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w420dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("DEPRECATION")
class SettingsCombinationInteractionTest {
    @Test fun defaultIsSystemAndItsThreePresetsAreImmediatelyBelowTheSelection() {
        val f = Fixture()
        draw(f.view)
        assertEquals(MeteringCombinationSelectionMode.SYSTEM, f.state.meteringCombinationSelectionMode)
        assertTrue(f.option("系统预设").isSelected)
        assertTrue(f.option("高精度").isSelected)
        assertNotNull(f.option("稳定"))
        assertNotNull(f.option("兼容"))
        val selection = bounds(f.option("系统预设"))
        val preset = bounds(f.option("高精度"))
        assertTrue(preset.top >= selection.bottom)
        assertTrue(preset.top - selection.bottom < selection.height())
    }

    @Test fun selectingManualEntersTheChooserAndRemovesPresetLayoutAndAccessibilityTargets() {
        val f = Fixture()
        draw(f.view)
        val exposureBefore = bounds(find(f.view, "曝光预览: 关闭")).top
        f.tap("手动选择")
        draw(f.view)
        assertEquals(1, f.chooserRequests)
        assertEquals(MeteringCombinationSelectionMode.MANUAL, f.state.meteringCombinationSelectionMode)
        assertTrue(f.option("手动选择").isSelected)
        assertTrue(findAll(f.view, "系统测光组合: 高精度").isEmpty())
        assertTrue(findAll(f.view, "系统测光组合: 稳定").isEmpty())
        assertTrue(findAll(f.view, "系统测光组合: 兼容").isEmpty())
        assertTrue(bounds(find(f.view, "曝光预览: 关闭")).top < exposureBefore)
    }

    @Test fun tappingSelectedManualReopensWithoutChangingTheSavedPreset() {
        val f = Fixture()
        f.state.meteringPipelineMode = MeteringPipelineMode.ISOLATED
        draw(f.view)
        f.tap("手动选择")
        draw(f.view)
        f.tap("手动选择")
        draw(f.view)
        f.tap("手动选择")
        assertEquals(3, f.chooserRequests)
        assertEquals(listOf(SettingKey.COMBINATION_SELECTION), f.changed)
        assertEquals(MeteringPipelineMode.ISOLATED, f.state.meteringPipelineMode)
    }

    @Test fun switchingBackToSystemRestoresThePreviouslySelectedPresetAndPersistsBothValues() {
        val f = Fixture()
        draw(f.view)
        f.tap("稳定")
        draw(f.view)
        f.tap("手动选择")
        draw(f.view)
        f.tap("系统预设")
        draw(f.view)
        assertTrue(f.option("稳定").isSelected)
        assertEquals(MeteringPipelineMode.ISOLATED, f.state.meteringPipelineMode)
        assertEquals(1, f.chooserRequests)
        val restored = MeterState(f.context)
        assertEquals(MeteringCombinationSelectionMode.SYSTEM, restored.meteringCombinationSelectionMode)
        assertEquals(MeteringPipelineMode.ISOLATED, restored.meteringPipelineMode)
    }

    @Test fun tappingSelectedSystemDoesNotRestartSelectionOrChangeItsPreset() {
        val f = Fixture()
        draw(f.view)
        f.tap("系统预设")
        assertTrue(f.changed.isEmpty())
        assertEquals(0, f.chooserRequests)
        assertEquals(MeteringPipelineMode.AUTO, f.state.meteringPipelineMode)
    }

    @Test fun accessibilityCanEnterAndReenterManualSelection() {
        val f = Fixture()
        draw(f.view)
        repeat(2) {
            val rect = bounds(f.option("手动选择"))
            val helper = ReflectionHelpers.getField<CanvasAccessibilityHelper>(f.view, "accessibilityHelper")
            val id = requireNotNull(helper.nodeIdAt(rect.exactCenterX(), rect.exactCenterY()))
            assertTrue(f.view.accessibilityNodeProvider.performAction(id, AccessibilityNodeInfo.ACTION_CLICK, null))
            draw(f.view)
        }
        assertEquals(2, f.chooserRequests)
        assertEquals(listOf(SettingKey.COMBINATION_SELECTION), f.changed)
    }

    @Test fun stalePresetHitTargetCannotChangeModeWhileManualIsActiveBeforeRedraw() {
        val f = Fixture()
        draw(f.view)
        val oldTarget = bounds(f.option("兼容"))
        f.state.meteringCombinationSelectionMode = MeteringCombinationSelectionMode.MANUAL
        tap(f.view, oldTarget)
        assertEquals(MeteringPipelineMode.AUTO, f.state.meteringPipelineMode)
        assertTrue(f.changed.isEmpty())
    }

    @Test fun hidingPresetsDoesNotReuseTheirAccessibilityIdsForOtherSettings() {
        val f = Fixture()
        draw(f.view)
        val oldTarget = bounds(f.option("兼容"))
        val helper = ReflectionHelpers.getField<CanvasAccessibilityHelper>(f.view, "accessibilityHelper")
        val oldId = requireNotNull(helper.nodeIdAt(oldTarget.exactCenterX(), oldTarget.exactCenterY()))
        f.tap("手动选择")
        draw(f.view)
        assertNull(f.view.accessibilityNodeProvider.createAccessibilityNodeInfo(oldId))
        assertFalse(f.view.accessibilityNodeProvider.performAction(oldId, AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(listOf(SettingKey.COMBINATION_SELECTION), f.changed)
    }

    @Test fun meterLayoutRoutesBothManualTapsToTheExistingCombinationScreen() {
        val f = Fixture()
        val modes = mutableListOf<MeteringCombinationSelectionMode>()
        val tracker = Proxy.newProxyInstance(ZoneMarkerTracker::class.java.classLoader,
            arrayOf(ZoneMarkerTracker::class.java)) { _, method, _ ->
            if (method.returnType == java.lang.Boolean.TYPE) false else null
        } as ZoneMarkerTracker
        val layout = MeterLayout(f.context, f.state,
            trackerFactory = ZoneMarkerTrackerFactory { _, _, _ -> tracker })
        layout.listener = Proxy.newProxyInstance(MeterLayout.Listener::class.java.classLoader,
            arrayOf(MeterLayout.Listener::class.java)) { _, method, arguments ->
            if (method.name == "onCombinationSelectionModeChanged") {
                modes.add(arguments!![0] as MeteringCombinationSelectionMode)
                // Replace the Activity's hardware probe with one available candidate.
                layout.showCombinationSelection(0, 1, false, "准备组合")
            }
            null
        } as MeterLayout.Listener
        size(layout)
        repeat(2) {
            layout.showSettings()
            size(layout.settingsView)
            draw(layout.settingsView)
            tap(layout.settingsView, bounds(find(layout.settingsView, "系统测光组合: 手动选择")))
            assertTrue(layout.isCombinationSelectionOpen)
            assertFalse(layout.isSettingsOpen)
            layout.closeCombinationSelection()
        }
        assertEquals(listOf(MeteringCombinationSelectionMode.MANUAL, MeteringCombinationSelectionMode.MANUAL), modes)
    }

    @Test fun renderSystemAndManualCardsInBothThemesAndLanguages() {
        val output = File(requireNotNull(System.getProperty("uiPreview.output")), "metering-combination")
            .apply { mkdirs() }
        for (english in listOf(false, true)) for (dark in listOf(false, true)) for (manual in listOf(false, true)) {
            val f = Fixture()
            f.state.menuLanguage = if (english) MenuLanguage.ENGLISH else MenuLanguage.CHINESE
            f.state.appTheme = if (dark) AppTheme.DARK else AppTheme.LIGHT
            f.state.meteringCombinationSelectionMode = if (manual) MeteringCombinationSelectionMode.MANUAL
                else MeteringCombinationSelectionMode.SYSTEM
            val filename = "${if (english) "en" else "zh"}-${if (dark) "dark" else "light"}-${if (manual) "manual" else "system"}.png"
            draw(f.view, File(output, filename))
            val prefix = if (english) "System metering combination" else "系统测光组合"
            val label = if (english) "High accuracy" else "高精度"
            assertEquals(if (manual) 0 else 1, findAll(f.view, "$prefix: $label").size)
        }
    }

    private class Fixture {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Lightstop)
        init { context.getSharedPreferences("raw_light_meter_state", Context.MODE_PRIVATE).edit().clear().commit() }
        val state = MeterState(context).apply { menuLanguage = MenuLanguage.CHINESE }
        val view = SettingsView(context, state)
        val changed = mutableListOf<SettingKey>()
        var chooserRequests = 0
        init {
            view.listener = object : SettingsView.Listener {
                override fun onCloseRequested() = Unit
                override fun onSettingChanged(key: SettingKey) {
                    changed.add(key)
                    if (key == SettingKey.COMBINATION_SELECTION &&
                        state.meteringCombinationSelectionMode == MeteringCombinationSelectionMode.MANUAL
                    ) chooserRequests++
                }
                override fun onManualCombinationSelectionRequested() { chooserRequests++ }
                override fun onSettingRejected(key: SettingKey, value: String) { fail("Unexpected rejection: $key=$value") }
                override fun onActionRequested(key: SettingActionKey) = Unit
            }
            size(view)
        }
        fun option(label: String) = find(view, "系统测光组合: $label")
        fun tap(label: String) = tap(view, bounds(option(label)))
    }

    private companion object {
        fun size(view: View) {
            view.measure(View.MeasureSpec.makeMeasureSpec(840, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 840, 1800)
        }
        fun draw(view: View, file: File? = null) {
            val bitmap = Bitmap.createBitmap(840, 1800, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                file?.outputStream()?.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
        fun findAll(view: View, label: String) = view.accessibilityNodeProvider
            .findAccessibilityNodeInfosByText(label, View.NO_ID).orEmpty()
            .filter { it.contentDescription.toString() == label }
        fun find(view: View, label: String): AccessibilityNodeInfo = findAll(view, label).single()
        fun bounds(info: AccessibilityNodeInfo) = Rect().also { info.getBoundsInParent(it); info.recycle() }
        fun tap(view: View, rect: Rect) {
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                MotionEvent.obtain(0, 10, action, rect.exactCenterX(), rect.exactCenterY(), 0).also {
                    view.onTouchEvent(it)
                    it.recycle()
                }
            }
        }
    }
}
