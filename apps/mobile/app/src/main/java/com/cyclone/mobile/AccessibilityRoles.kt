package com.cyclone.mobile

/**
 * TalkBack-oriented role inference and host folding for accessibility snapshots.
 * Pure functions so JVM unit tests can cover Clock tabs, Files rows, and Calculator digits.
 */
object AccessibilityRoles {
    private val TAB_CLASS = Regex("(?:^|[.$])(tablayout|tabview|tabwidget|tabitem|tabrow|tab)(?:$|[.$])", RegexOption.IGNORE_CASE)
    private val TAB_BAR_CLASS = Regex("tablayout|tabwidget|tabrow|tabbar", RegexOption.IGNORE_CASE)
    private val ROW_CLASS = Regex("preference|listitem|recyclerview|listview", RegexOption.IGNORE_CASE)
    private val HOST_ROLES = setOf("button", "tab", "row", "textbox", "switch", "checkbox")
    private val DATE_OR_SIZE = Regex(
        """(?i)(?:^(?:mon|tue|wed|thu|fri|sat|sun)\b|\b(?:jan|feb|mar|apr|may|jun|jul|aug|sept?|oct|nov|dec)\b|""" +
            """\d+[.,]?\d*\s*(?:mb|gb|kb|bytes)\b|\b\d+\s+(?:days?|hours?|minutes?|weeks?)\s+ago\b)""",
    )
    private val FILENAME = Regex("""(?i)[\w.-]+\.(apk|zip|pdf|png|jpe?g|txt|mp4|csv|json|html|bin)\b""")

    fun inferRole(
        className: String,
        clickable: Boolean = false,
        editable: Boolean = false,
        checkable: Boolean = false,
        scrollable: Boolean = false,
        selected: Boolean = false,
        text: String = "",
        contentDescription: String = "",
        resourceId: String = "",
        parentClassName: String = "",
        actions: List<String> = emptyList(),
    ): String {
        val cls = className.lowercase()
        val parentCls = parentClassName.lowercase()
        val resource = resourceId.lowercase()
        if (editable || "edittext" in cls) return "textbox"
        if (isTab(cls, resource, parentCls, selected, clickable)) return "tab"
        if ("checkbox" in cls || checkable) return "checkbox"
        if ("switch" in cls) return "switch"
        if (isRow(cls, resource, parentCls)) return "row"
        val labeled = text.isNotBlank() || contentDescription.isNotBlank()
        val canClick = clickable || "ACTION_CLICK" in actions
        if ("button" in cls || (canClick && labeled)) return "button"
        if ("image" in cls) return "image"
        if (scrollable) return "scroll_container"
        if ("textview" in cls) return "text"
        return "generic"
    }

    fun isTab(
        className: String,
        resourceId: String = "",
        parentClassName: String = "",
        selected: Boolean = false,
        clickable: Boolean = false,
    ): Boolean {
        val cls = className.lowercase()
        if (cls.contains("table") || cls.contains("tablet")) return false
        if (TAB_CLASS.containsMatchIn(cls)) return true
        if (resourceId.lowercase().contains("tab") && !resourceId.lowercase().contains("table")) return true
        val inTabBar = TAB_BAR_CLASS.containsMatchIn(parentClassName)
        return inTabBar && (selected || clickable || className.contains("TextView", ignoreCase = true))
    }

    fun isRow(className: String, resourceId: String = "", parentClassName: String = ""): Boolean {
        return ROW_CLASS.containsMatchIn(className) ||
            ROW_CLASS.containsMatchIn(resourceId) ||
            ROW_CLASS.containsMatchIn(parentClassName)
    }

    fun isHostRole(role: String): Boolean = role.lowercase() in HOST_ROLES

    fun isActivatable(clickable: Boolean, actions: List<String>): Boolean =
        clickable || "ACTION_CLICK" in actions || "ACTION_SELECT" in actions

    fun preferClickableAncestor(role: String, nodeClickable: Boolean, actions: List<String>): Boolean {
        val roleName = role.lowercase()
        if (roleName in setOf("text", "image")) return true
        if (!isActivatable(nodeClickable, actions)) return true
        return roleName == "generic"
    }

    fun isPublishedInteractive(
        visibleToUser: Boolean,
        interactive: Boolean,
        boundsWidth: Int,
        boundsHeight: Int,
    ): Boolean {
        if (visibleToUser) return true
        return interactive && boundsWidth > 0 && boundsHeight > 0
    }

    fun joinHostLabels(parts: List<String>): String {
        val unique = parts.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (unique.isEmpty()) return ""
        val best = unique.maxByOrNull(::hostLabelScore) ?: return ""
        val rest = unique.filter { it != best }
        return (listOf(best) + rest).joinToString(" ")
    }

    internal fun hostLabelScore(label: String): Int {
        val t = label.trim()
        var score = t.length.coerceAtMost(80)
        if (FILENAME.containsMatchIn(t)) score += 1000
        if (t.contains('.')) score += 80
        if (DATE_OR_SIZE.containsMatchIn(t)) score -= 500
        return score
    }

    fun foldTalkBackHosts(nodes: List<UiNodeSnapshot>): List<UiNodeSnapshot> {
        if (nodes.isEmpty()) return nodes
        val byId = nodes.associateBy { it.id }
        val labels = linkedMapOf<String, MutableList<String>>()
        val roles = linkedMapOf<String, String>()
        for (node in nodes) {
            if (node.clickable || node.editable || "ACTION_CLICK" in node.actions) continue
            if (node.role !in setOf("text", "generic", "image", "")) continue
            val ownLabel = node.text.trim().ifBlank { node.contentDescription.trim() }
            if (ownLabel.isBlank()) continue
            var parentId = node.parentId
            repeat(6) {
                val parent = parentId?.let(byId::get) ?: return@repeat
                val host = parent.editable || isActivatable(parent.clickable, parent.actions)
                if (host) {
                    labels.getOrPut(parent.id) { mutableListOf() }.add(ownLabel)
                    roles[parent.id] = when {
                        isRow(parent.className, parent.resourceId) -> "row"
                        else -> inferRole(
                            className = parent.className,
                            clickable = true,
                            editable = parent.editable,
                            checkable = parent.checkable,
                            scrollable = parent.scrollable,
                            selected = parent.selected,
                            text = parent.text.ifBlank { ownLabel },
                            contentDescription = parent.contentDescription,
                            resourceId = parent.resourceId,
                            actions = parent.actions,
                        )
                    }
                    return@repeat
                }
                parentId = parent.parentId
            }
        }
        if (labels.isEmpty() && roles.isEmpty()) return nodes
        return nodes.map { node ->
            val promoted = labels[node.id]
            val role = roles[node.id]
            if (promoted == null && role == null) node
            else node.copy(
                text = node.text.ifBlank { joinHostLabels(promoted.orEmpty()) },
                clickable = true,
                role = role ?: node.role,
                actions = if ("ACTION_CLICK" in node.actions) node.actions else node.actions + "ACTION_CLICK",
            )
        }
    }
}
