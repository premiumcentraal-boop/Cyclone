package com.cyclone.teamworksniper.runtime

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationManagerCompat
import com.cyclone.teamworksniper.data.TriggerEvent
import com.cyclone.teamworksniper.teamwork.SemanticNode
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

object TeamworkLauncher {
    const val PACKAGE = "tech.picnic.workapp"

    fun open(context: Context, pending: PendingIntent? = null): String {
        if (pending != null) {
            runCatching { pending.send() }.onSuccess { return "notification-pending-intent" }
        }
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE)
            ?: return "launch-intent-unavailable"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching {
            context.startActivity(intent)
            "package-launch-intent"
        }.getOrElse { "launch-failed:" + it.javaClass.simpleName }
    }
}

data class PermissionState(
    val notificationAccess: Boolean,
    val accessibilityAccess: Boolean,
) {
    val ready: Boolean get() = notificationAccess && accessibilityAccess
}

object PermissionChecker {
    fun read(context: Context) = PermissionState(
        notificationAccess = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName),
        accessibilityAccess = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty().split(':').any {
            it.equals(ComponentName(context, TeamworkAccessibilityService::class.java).flattenToString(), true)
        },
    )
}

object SniperCoordinator {
    private val pending = AtomicReference<TriggerEvent?>(null)
    private var ref: WeakReference<TeamworkAccessibilityService>? = null

    @Synchronized
    fun attach(service: TeamworkAccessibilityService) {
        ref = WeakReference(service)
        pending.get()?.let(service::requestEvaluation)
    }

    @Synchronized
    fun detach(service: TeamworkAccessibilityService) {
        if (ref?.get() === service) ref = null
    }

    fun submit(trigger: TriggerEvent) {
        pending.set(trigger)
        ref?.get()?.requestEvaluation(trigger)
    }

    fun current() = pending.get()
    fun consume(trigger: TriggerEvent) {
        pending.compareAndSet(trigger, null)
    }
}

object AccessibilitySemanticTree {
    fun snapshot(root: AccessibilityNodeInfo): SemanticNode {
        val children = buildList {
            for (index in 0 until root.childCount) {
                val child = root.getChild(index) ?: continue
                try {
                    add(snapshot(child))
                } finally {
                    child.recycle()
                }
            }
        }
        return SemanticNode(
            text = root.text?.toString(),
            contentDescription = root.contentDescription?.toString(),
            resourceId = root.viewIdResourceName,
            className = root.className?.toString(),
            clickable = root.isClickable,
            scrollable = root.isScrollable,
            actions = root.actionList.mapNotNull { action ->
                action.label?.toString() ?: when (action.id) {
                    AccessibilityNodeInfo.ACTION_CLICK -> "ACTION_CLICK"
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "ACTION_SCROLL_FORWARD"
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "ACTION_SCROLL_BACKWARD"
                    else -> null
                }
            }.toSet(),
            children = children,
        )
    }

    fun nodeAtPath(root: AccessibilityNodeInfo, path: List<Int>): AccessibilityNodeInfo? {
        var current = AccessibilityNodeInfo.obtain(root)
        path.forEach { index ->
            val next = current.getChild(index) ?: run {
                current.recycle()
                return null
            }
            current.recycle()
            current = next
        }
        return current
    }

    fun firstScrollable(root: AccessibilityNodeInfo, action: Int): AccessibilityNodeInfo? {
        if (root.isScrollable && root.actionList.any { it.id == action }) return AccessibilityNodeInfo.obtain(root)
        for (index in 0 until root.childCount) {
            val child = root.getChild(index) ?: continue
            val found = try {
                firstScrollable(child, action)
            } finally {
                child.recycle()
            }
            if (found != null) return found
        }
        return null
    }

    fun nearestClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        repeat(8) {
            val candidate = current ?: return null
            if (candidate.isClickable || candidate.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                return candidate
            }
            val parent = candidate.parent
            candidate.recycle()
            current = parent
        }
        current?.recycle()
        return null
    }

    fun ownSemanticText(node: AccessibilityNodeInfo): String =
        listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            .joinToString(" ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    fun findClickableByOwnText(
        root: AccessibilityNodeInfo,
        predicate: (String) -> Boolean,
    ): AccessibilityNodeInfo? {
        val own = ownSemanticText(root)
        if (own.isNotBlank() && predicate(own)) {
            nearestClickable(root)?.let { return it }
        }
        for (index in 0 until root.childCount) {
            val child = root.getChild(index) ?: continue
            val found = try {
                findClickableByOwnText(child, predicate)
            } finally {
                child.recycle()
            }
            if (found != null) return found
        }
        return null
    }

    fun findClickableByResourceId(root: AccessibilityNodeInfo, resourceId: String): AccessibilityNodeInfo? {
        if (root.viewIdResourceName == resourceId) {
            nearestClickable(root)?.let { return it }
        }
        for (index in 0 until root.childCount) {
            val child = root.getChild(index) ?: continue
            val found = try {
                findClickableByResourceId(child, resourceId)
            } finally {
                child.recycle()
            }
            if (found != null) return found
        }
        return null
    }

    fun findClickableByResourceIdContains(root: AccessibilityNodeInfo, pattern: Regex): AccessibilityNodeInfo? {
        val id = root.viewIdResourceName.orEmpty()
        if (id.isNotBlank() && pattern.containsMatchIn(id)) {
            nearestClickable(root)?.let { return it }
        }
        for (index in 0 until root.childCount) {
            val child = root.getChild(index) ?: continue
            val found = try {
                findClickableByResourceIdContains(child, pattern)
            } finally {
                child.recycle()
            }
            if (found != null) return found
        }
        return null
    }
}
