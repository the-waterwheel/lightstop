package com.lightmeter.rawmeter

import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider

/**
 * Minimal platform [AccessibilityNodeProvider] for a Canvas-drawn view.
 *
 * Each virtual node maps one logical control to its drawn bounds, so TalkBack, keyboard and mouse
 * users can reach controls that have no real child Views. It uses only platform APIs, keeping the
 * project's no-AndroidX build. Touch behaviour is unchanged: [onVirtualClick] is expected to run
 * the same business action as a tap.
 */
internal class CanvasAccessibilityHelper(
    private val host: View,
    private val onVirtualClick: (Int) -> Boolean,
) {
    data class VirtualNode(
        val id: Int,
        val bounds: RectF,
        val label: CharSequence,
        val selected: Boolean = false,
    )

    private var nodes: List<VirtualNode> = emptyList()
    private var hoveredId = Int.MIN_VALUE

    fun update(newNodes: List<VirtualNode>) {
        nodes = newNodes
    }

    fun nodeIdAt(x: Float, y: Float): Int? =
        nodes.firstOrNull { it.bounds.contains(x, y) }?.id

    /** Handles touch-exploration hover, returning true when the event was consumed. */
    fun handleHoverEvent(event: MotionEvent, manager: AccessibilityManager?): Boolean {
        if (manager?.isEnabled != true || !manager.isTouchExplorationEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                val id = nodeIdAt(event.x, event.y) ?: Int.MIN_VALUE
                if (id != hoveredId) {
                    updateHovered(id, manager)
                    return true
                }
            }

            MotionEvent.ACTION_HOVER_EXIT -> {
                if (hoveredId != Int.MIN_VALUE) {
                    updateHovered(Int.MIN_VALUE, manager)
                    return true
                }
            }
        }
        return false
    }

    private fun updateHovered(virtualViewId: Int, manager: AccessibilityManager) {
        val previous = hoveredId
        hoveredId = virtualViewId
        if (virtualViewId == previous) return
        if (previous != Int.MIN_VALUE) {
            sendEvent(previous, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT, manager)
        }
        if (virtualViewId != Int.MIN_VALUE) {
            sendEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER, manager)
        }
    }

    private fun sendEvent(virtualViewId: Int, eventType: Int, manager: AccessibilityManager) {
        if (!manager.isEnabled) return
        val node = provider.createAccessibilityNodeInfo(virtualViewId) ?: return
        val event = AccessibilityEvent.obtain(eventType).apply {
            packageName = host.context.packageName
            className = "android.view.View"
            contentDescription = node.contentDescription
            isEnabled = true
            setSource(host, virtualViewId)
        }
        manager.sendAccessibilityEvent(event)
    }

    val provider: AccessibilityNodeProvider = object : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            if (virtualViewId == HOST_VIEW_ID) return createHostNode()
            val node = nodes.firstOrNull { it.id == virtualViewId } ?: return null
            return AccessibilityNodeInfo.obtain().apply {
                setSource(host, node.id)
                setParent(host)
                className = "android.view.View"
                packageName = host.context.packageName
                isEnabled = true
                isVisibleToUser = true
                isFocusable = true
                isClickable = true
                isSelected = node.selected
                contentDescription = node.label
                val rect = Rect()
                node.bounds.round(rect)
                setBoundsInParent(rect)
                setBoundsInScreen(hostLocationOnScreen(rect))
                addAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }

        override fun findAccessibilityNodeInfosByText(
            text: String?,
            virtualViewId: Int,
        ): MutableList<AccessibilityNodeInfo> {
            val query = text?.lowercase().orEmpty()
            if (query.isEmpty()) return mutableListOf()
            return nodes.filter { it.label.toString().lowercase().contains(query) }
                .mapNotNull { createAccessibilityNodeInfo(it.id) }
                .toMutableList()
        }

        override fun performAction(
            virtualViewId: Int,
            action: Int,
            arguments: android.os.Bundle?,
        ): Boolean {
            if (virtualViewId == HOST_VIEW_ID) {
                return host.performAccessibilityAction(action, arguments)
            }
            return if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                onVirtualClick(virtualViewId)
            } else {
                false
            }
        }

        private fun createHostNode(): AccessibilityNodeInfo =
            AccessibilityNodeInfo.obtain(host).apply {
                packageName = host.context.packageName
                nodes.forEach { addChild(host, it.id) }
            }
    }

    private fun hostLocationOnScreen(boundsInHost: Rect): Rect {
        val location = IntArray(2)
        host.getLocationOnScreen(location)
        return Rect(
            boundsInHost.left + location[0],
            boundsInHost.top + location[1],
            boundsInHost.right + location[0],
            boundsInHost.bottom + location[1],
        )
    }

    private companion object {
        private const val HOST_VIEW_ID = -1
    }
}
