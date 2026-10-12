package com.cyclone.mobile.market

import com.cyclone.mobile.automation.AutomationDefinition

data class LibraryApp(val packageName: String, val name: String, val skills: List<MarketListing>, val routines: List<AutomationDefinition>, val installed: Boolean)
data class LibraryResults(val apps: List<LibraryApp>, val skills: List<MarketListing>, val routines: List<AutomationDefinition>) {
    val empty get() = apps.isEmpty() && skills.isEmpty() && routines.isEmpty()
}

/** Shared, deterministic grouping/search. Apps and Categories are not two views of the same routine list anymore. */
object SkillLibrary {
    fun apps(labels: Map<String, String>, skills: List<MarketListing>, routines: List<AutomationDefinition>): List<LibraryApp> {
        val packages = (labels.keys + skills.flatMap { it.apps } + routines.flatMap { it.appPackages }).distinct()
        return packages.map { pkg ->
            LibraryApp(pkg, labels[pkg] ?: if (pkg == InstagramSkills.PACKAGE) "Instagram" else pkg.substringAfterLast('.'),
                skills.filter { pkg in it.apps }, routines.filter { pkg in it.appPackages }, pkg in labels)
        }.sortedWith(compareByDescending<LibraryApp> { it.skills.size + it.routines.size }.thenBy { it.name.lowercase() })
    }

    fun search(query: String, apps: List<LibraryApp>, skills: List<MarketListing>, routines: List<AutomationDefinition>): LibraryResults {
        val q = query.trim()
        val matchingApps = apps.filter { it.name.contains(q, true) || it.packageName.contains(q, true) }
        val packages = matchingApps.map { it.packageName }.toSet()
        return LibraryResults(matchingApps,
            skills.filter { it.name.contains(q, true) || it.summary.contains(q, true) || it.apps.any(packages::contains) },
            routines.filter { it.name.contains(q, true) || it.description.contains(q, true) || it.appPackages.any(packages::contains) })
    }
}
