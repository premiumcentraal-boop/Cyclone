package com.cyclone.teamworksniper.ui.overlay

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Copies semantic content plus presentation bounds. It never performs or identifies a claim action. */
object AccessibilityOverlaySnapshot {
    fun capture(root: AccessibilityNodeInfo): ScheduleSemanticNode = captureNode(root, 0)

    private fun captureNode(node: AccessibilityNodeInfo, depth: Int): ScheduleSemanticNode {
        val screen = Rect().also(node::getBoundsInScreen)
        val children = if (depth >= MAX_DEPTH) {
            emptyList()
        } else {
            buildList {
                for (index in 0 until node.childCount.coerceAtMost(MAX_CHILDREN)) {
                    val child = node.getChild(index) ?: continue
                    try {
                        add(captureNode(child, depth + 1))
                    } finally {
                        child.recycle()
                    }
                }
            }
        }
        return ScheduleSemanticNode(
            packageName = node.packageName?.toString(),
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            resourceId = node.viewIdResourceName,
            className = node.className?.toString(),
            visible = node.isVisibleToUser,
            bounds = OverlayRect(screen.left, screen.top, screen.right, screen.bottom),
            children = children,
        )
    }

    private const val MAX_DEPTH = 40
    private const val MAX_CHILDREN = 200
}
