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
        val enabled: Boolean = true,
    )

    private var nodes: List<VirtualNode> = emptyList()
    private var hoveredId = Int.MIN_VALUE
    private var accessibilityFocusedId = Int.MIN_VALUE

    fun update(newNodes: List<VirtualNode>) {
        val previous = nodes
        nodes = newNodes
        if (accessibilityFocusedId != Int.MIN_VALUE && node(accessibilityFocusedId) == null) {
            // Send the clear event using the removed node's own label: it is already absent from
            // the new list, so a provider lookup would silently drop the event.
            val removed = previous.firstOrNull { it.id == accessibilityFocusedId }
            clearAccessibilityFocus(accessibilityFocusedId, removed)
        }
        if (hoveredId != Int.MIN_VALUE && node(hoveredId) == null) hoveredId = Int.MIN_VALUE
    }

    fun clearFocusForHostExit() {
        if (accessibilityFocusedId != Int.MIN_VALUE) {
            clearAccessibilityFocus(accessibilityFocusedId, node(accessibilityFocusedId))
        }
        hoveredId = Int.MIN_VALUE
    }

    fun nodeIdAt(x: Float, y: Float): Int? =
        nodes.firstOrNull { it.enabled && it.bounds.width() > 0f && it.bounds.height() > 0f && it.bounds.contains(x, y) }?.id

    private fun node(id: Int): VirtualNode? = nodes.firstOrNull {
        it.id == id && it.enabled && it.bounds.width() > 0f && it.bounds.height() > 0f
    }

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

    private fun sendEvent(virtualViewId: Int, eventType: Int, manager: AccessibilityManager?) {
        sendEvent(node(virtualViewId), virtualViewId, eventType, manager)
    }

    private fun sendEvent(
        node: VirtualNode?,
        virtualViewId: Int,
        eventType: Int,
        manager: AccessibilityManager?,
    ) {
        if (manager?.isEnabled != true) return
        val label = node?.label
            ?: provider.createAccessibilityNodeInfo(virtualViewId)?.contentDescription
            ?: return
        val event = AccessibilityEvent.obtain(eventType).apply {
            packageName = host.context.packageName
            className = "android.view.View"
            contentDescription = label
            isEnabled = true
            setSource(host, virtualViewId)
        }
        manager.sendAccessibilityEvent(event)
    }

    val provider: AccessibilityNodeProvider = object : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            if (virtualViewId == HOST_VIEW_ID) return createHostNode()
            val node = node(virtualViewId) ?: return null
            return AccessibilityNodeInfo.obtain().apply {
                setSource(host, node.id)
                setParent(host)
                className = "android.view.View"
                packageName = host.context.packageName
                isEnabled = node.enabled
                isVisibleToUser = host.visibility == View.VISIBLE && host.isShown
                isFocusable = true
                isClickable = node.enabled
                isAccessibilityFocused = accessibilityFocusedId == node.id
                isSelected = node.selected
                contentDescription = node.label
                val rect = Rect()
                node.bounds.round(rect)
                setBoundsInParent(rect)
                setBoundsInScreen(hostLocationOnScreen(rect))
                addAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (accessibilityFocusedId == node.id) {
                    addAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
                } else {
                    addAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                }
            }
        }

        override fun findFocus(focus: Int): AccessibilityNodeInfo? =
            if (focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY &&
                accessibilityFocusedId != Int.MIN_VALUE
            ) createAccessibilityNodeInfo(accessibilityFocusedId) else null

        override fun findAccessibilityNodeInfosByText(
            text: String?,
            virtualViewId: Int,
        ): MutableList<AccessibilityNodeInfo> {
            val query = text?.lowercase().orEmpty()
            if (query.isEmpty()) return mutableListOf()
            return nodes.filter { node(it.id) != null && it.label.toString().lowercase().contains(query) }
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
            if (node(virtualViewId) == null) return false
            return when (action) {
                AccessibilityNodeInfo.ACTION_CLICK -> onVirtualClick(virtualViewId).also { clicked ->
                    if (clicked) sendEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED, accessibilityManager())
                }
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> requestAccessibilityFocus(virtualViewId)
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> clearAccessibilityFocus(virtualViewId)
                else -> false
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

    private fun requestAccessibilityFocus(id: Int): Boolean {
        if (node(id) == null || accessibilityFocusedId == id) return false
        val previous = accessibilityFocusedId
        accessibilityFocusedId = id
        if (previous != Int.MIN_VALUE) {
            sendEvent(previous, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED, accessibilityManager())
        }
        sendEvent(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, accessibilityManager())
        host.invalidate()
        return true
    }

    private fun clearAccessibilityFocus(id: Int): Boolean =
        clearAccessibilityFocus(id, node(id))

    private fun clearAccessibilityFocus(id: Int, removedNode: VirtualNode?): Boolean {
        if (accessibilityFocusedId != id) return false
        accessibilityFocusedId = Int.MIN_VALUE
        sendEvent(
            removedNode,
            id,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,
            accessibilityManager(),
        )
        host.invalidate()
        return true
    }

    private fun accessibilityManager(): AccessibilityManager? =
        host.context.getSystemService(AccessibilityManager::class.java)

    private companion object {
        private const val HOST_VIEW_ID = -1
    }
}
