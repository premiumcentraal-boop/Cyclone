package com.cyclone.mobile.runtime.workspaces

/** Essentials cannot be deselected, and do not consume the owner's optional-app allowance. */
object ProfileAppSelection {
    const val MAX_OPTIONAL = 50

    fun normalize(apps: List<ProfileApp>, selected: Set<String>): Set<String> =
        selected.intersect(apps.map { it.packageName }.toSet()) +
            apps.filter { it.automatic }.map { it.packageName }

    fun toggle(apps: List<ProfileApp>, selected: Set<String>, pkg: String, checked: Boolean): Set<String> =
        normalize(apps, if (checked) selected + pkg else selected - pkg)

    fun optionalCount(apps: List<ProfileApp>, selected: Set<String>): Int =
        apps.count { !it.automatic && it.packageName in selected }

    fun canConfirm(apps: List<ProfileApp>, selected: Set<String>): Boolean =
        apps.isNotEmpty() && normalize(apps, selected).isNotEmpty() && optionalCount(apps, selected) <= MAX_OPTIONAL
}
