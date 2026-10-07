package com.cyclone.mobile.market

/** Portable, reviewed Android routes. These are live-screen guides, never coordinate macros or replay proofs. */
data class NativeSkill(
    val listing: MarketListing,
    val route: List<String>,
    val checks: List<String>,
    val limitation: String,
    val evidence: String,
) {
    val marker get() = "Instagram skill: ${listing.name}."
    fun guidance(): String = buildString {
        append("Built-in tested Android skill: ${listing.name}.\n")
        append("Reviewed route: ${route.joinToString(" > ")}.\n")
        append("Verify: ${checks.joinToString("; ")}.\n")
        append("Limits: $limitation\n")
        append("This route was observed on one Android Instagram account, not every device/version. Use current semantic refs, ")
        append("never saved refs or coordinates. Re-observe each changed page; unchanged taps do not prove arrival. ")
        append("Respect current language, permissions and account variants. Prefer the phone's learned map, then this guide. ")
        append("After two failures at one target try one safe alternative, then report the missing prerequisite. ")
        append("Do not repeat a publish or account-creation submission without checking its result. Keep owner approvals and secure input boundaries.")
    }
}

object InstagramSkills {
    const val PACKAGE = "com.instagram.android"
    const val ACCOUNT_SETUP = "cyclone.instagram-account-setup"
    const val POST = "cyclone.instagram-prepare-post"
    private val publisher = Publisher("cyclone", "Cyclone", true)

    private fun skill(
        id: String, name: String, summary: String, goal: String, route: List<String>, checks: List<String>,
        limitation: String, evidence: String, inputs: List<MarketInput> = emptyList(), asks: List<String> = emptyList(),
    ): NativeSkill = NativeSkill(
        MarketListing("cyclone.instagram-$id", ListingKind.RECIPE, "1.0.0", name, publisher, summary, "Instagram", "◎",
            "Instagram skill: $name. $goal", inputs, listOf(PACKAGE), checks, asks, listOf(PACKAGE), featured = id == "prepare-post"),
        route, checks, limitation, evidence,
    )

    val all: List<NativeSkill> = listOf(
        skill("prepare-post", "Prepare an Instagram post", "Choose your photo, add a caption and review it before sharing.",
            "Use only the photo identified by {media}. Caption: {caption}. Mode: {mode}. Open Instagram Create > Post, verify the selected photo, Next to editor, then Next to caption/review. In Review only stop before Share; in Publish with approval ask before Share, submit once and verify the post in my profile. No tags, location or crossposting.",
            listOf("Profile", "Create new", "Post", "New post picker", "Next", "Photo editor", "Next", "Caption and review"),
            listOf("Verify the selected photo", "Verify the caption", "Review before sharing", "Verify the published post when sharing is approved"),
            "Photo posting tested once. Editing subtools, multiple photos, video and alt text are not yet verified. Media access may need your help. If Sharing posts blocks review, dismiss the notice with Back. If focused typing fails, re-observe and use the caption field ref.",
            "Android Instagram 449.0.0.52.84; Pixel 8; successful photo publish 2026-10-07; ai-e24df644-6bbb-46d7-9d70-e7cff803a48b",
            listOf(MarketInput("media", "Photo filename or description"), MarketInput("caption", "Caption", required = false),
                MarketInput("mode", "Finish at", InputKind.CHOICE, "Review only", listOf("Review only", "Publish with approval"))),
            listOf("Sharing the post", "Access to photos, if needed")),
        skill("account-setup", "Set up an Instagram account", "Complete your details first, then follow the tested Android signup flow.",
            "Open the account setup form in Cyclone before starting. Never invent personal details, log out, clear app data or alter an existing account.",
            listOf("Account details in Cyclone", "Create new account", "Contact and verification", "Password", "Birthday", "Name and username", "Terms", "Onboarding", "Own profile"),
            listOf("Check every required detail before starting", "Use secure password delivery", "Retrieve SMS codes when available", "Verify the new signed-in profile"),
            "One phone-number signup tested. Email signup and the signed-in username-first variation have not been exercised end to end. Page order may change. Terms, CAPTCHA and unavailable verification need your decision; optional contacts, photos and follows are skipped.",
            "Packaged signup/instagram.json; Android Instagram 449.0.0.52.84; successful phone signup 2026-10-06",
            asks = listOf("Instagram's terms and account creation", "Verification that requires you", "Unexpected permissions")),
        skill("profile", "Open my Instagram profile", "Open your own profile and check the account you are using.",
            "Open Instagram and its Profile tab. Verify my own profile header. Do not edit anything or open suggested accounts.",
            listOf("Instagram", "Profile tab", "Own profile"), listOf("Verify the own-profile header"),
            "Works from the signed-in main navigation; a login or account chooser is a prerequisite.", "Profile benchmark: both approved models, 2026-10-06"),
        skill("profile-editor", "Open Instagram profile editing", "Reach your profile fields without changing them.",
            "Open my Instagram Profile > Edit profile. Inspect the visible field labels without typing, changing my picture or submitting changes.",
            listOf("Own profile", "Edit profile"), listOf("Verify Edit profile and its field labels"),
            "Editor landing tested. Name and username child edits are not verified; this skill makes no changes.", "ai-9f46fa4d-1537-480d-8edd-7e27be005301"),
        skill("search-accounts", "Search Instagram accounts", "Find an account through Instagram's Accounts search results.",
            "Open Instagram Search and explore > Search, search for {query}, then Accounts. Verify the query and results; do not follow, message or choose an ambiguous account.",
            listOf("Search and explore", "Search", "Query results", "Accounts"), listOf("Verify the query", "Verify Accounts results"),
            "Accounts results tested. Audio, Tags and Places search are not verified; no claim of video-viewer support.", "ai-b264e536-eed5-485f-b53d-010e5db249a7",
            listOf(MarketInput("query", "Account name or username"))),
        skill("comments", "Read Instagram comments", "Read comments on the post you currently have open.",
            "In Instagram use the currently open post's comment count to open Comments. Read visible comments and scroll once for more. Summarize them for me without typing, liking, replying or sharing. Report if there is no post open.",
            listOf("Open post", "Comment count", "Comments", "Scroll thread"), listOf("Verify Comments", "Verify additional comments after scrolling"),
            "Reading and thread scrolling tested; native clipboard copying, composer focus and reply flows are not verified.", "ai-20712810-773b-45a1-a979-1443b4940868"),
        skill("saved", "Open Instagram saved items", "Browse your saved categories without adding or removing anything.",
            "Open Instagram own Profile > Options > Saved. Verify the Saved categories; do not save, unsave or create a collection.",
            listOf("Own profile", "Options", "Saved"), listOf("Verify Saved categories"),
            "All, Collections, Series, Reels and Posts landings tested. Audio and collection creation not verified.", "ai-cacf9627-9ebc-4989-bd8c-bca7e2bebeee"),
        skill("share-profile", "Show my Instagram profile QR", "Display your profile QR code without sending it anywhere.",
            "Open Instagram own Profile > Share profile. Verify the QR/profile sharing screen. Do not send, download, copy or scan anything.",
            listOf("Own profile", "Share profile", "Profile QR"), listOf("Verify the profile QR screen"),
            "QR landing tested. Sending, downloading, copying and scanning not executed; Android Back was the verified return.", "ai-9b6d1483-17c7-4d16-9bae-a3eefb5dd79b"),
        skill("settings", "Open Instagram settings", "Find Settings and activity without changing any preferences.",
            "Open Instagram own Profile > Options > Settings and activity. Verify the settings menu. Do not toggle anything, add an account or log out.",
            listOf("Own profile", "Options", "Settings and activity"), listOf("Verify the settings menu"),
            "Menu and scrolling tested. Child setting changes are not part of this skill.", "ai-73dba2e4-25c1-4a54-b3f6-099d51d4ec4e"),
        skill("requests", "Check Instagram message requests", "Inspect message request folders without accepting or replying.",
            "Open Instagram Message > Requests; dismiss an optional privacy notice with Not now, then Hidden requests. Report folder availability without accepting, deleting or sending anything.",
            listOf("Message", "Requests", "Not now if shown", "Hidden requests"), listOf("Verify both request folders"),
            "Empty folders and return routes tested; handling actual requests is unverified.", "ai-c740090a-f9b4-4d7d-bfca-ff34d2ad246a"),
    )

    fun byId(id: String): NativeSkill? = all.firstOrNull { it.listing.id == id }
    fun forGoal(goal: String): NativeSkill? = all.firstOrNull { goal.startsWith(it.marker) }
    fun advice(query: String): String? {
        val terms = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 && it !in setOf("instagram", "open", "the", "and", "with", "for", "app") }
        if (terms.isEmpty()) return null
        return all.map { skill -> skill to terms.count { term ->
            (skill.listing.name + " " + skill.listing.summary + " " + skill.route.joinToString(" ")).contains(term, true)
        } }.filter { it.second > 0 }.sortedByDescending { it.second }.take(3)
            .takeIf { it.isNotEmpty() }?.joinToString("\n\n", prefix = "Built-in Android route advice (not a learned go_to handle):\n") { it.first.guidance() }
    }
}
