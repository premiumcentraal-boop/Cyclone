package com.cyclone.mobile.runtime.workspaces

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Plan 57 P0 (alpha.118): truthful profile errors, room on the phone, profile room on rooted phones, the debug file. */
class ProfileHardening57Test {
    private val create = ProfileSetupOperation.CREATE_SECONDARY_USER

    private fun kind(text: String, op: ProfileSetupOperation = create) = ProfileFailureClassifier.fromCommand(op, 1, text)?.kind

    // W2: Android's real words ---------------------------------------------------------------------------------------

    @Test fun `Android's per-type refusal is a limit, not a duplicate profile`() {
        // The owner's Profile C failure (2026-10-09): AOSP UserManagerService, verbatim.
        assertEquals(ProfileSetupFailureKind.USER_TYPE_LIMIT, kind("Error: couldn't create User: android.os.UserManager\$CheckedUserOperationException: " +
            "Cannot add more users of type android.os.usertype.full.SECONDARY. Maximum number of that type already exists."))
        assertEquals(ProfileSetupFailureKind.MAX_USERS_REACHED, kind("Error: couldn't create User: Cannot add user. Maximum user limit is reached."))
        assertEquals(ProfileSetupFailureKind.MAX_PROFILES_REACHED,
            kind("Error: Cannot add more profiles of type android.os.usertype.profile.MANAGED for user 0", ProfileSetupOperation.CREATE_MANAGED_PROFILE))
        assertEquals(ProfileSetupFailureKind.MAX_PROFILES_REACHED, kind("Cannot add more users of type android.os.usertype.profile.MANAGED. " +
            "Maximum number of that type already exists.", ProfileSetupOperation.CREATE_MANAGED_PROFILE))
        assertEquals(ProfileSetupFailureKind.USER_TYPE_DISABLED, kind("Error: Cannot add a user of disabled type android.os.usertype.full.SECONDARY."))
        assertEquals(ProfileSetupFailureKind.OEM_RESTRICTION, kind("Error: Cannot add user. DISALLOW_ADD_USER is enabled."))
        // A real duplicate still reads as one (the runtime re-lists users before believing it).
        assertEquals(ProfileSetupFailureKind.PROFILE_ALREADY_EXISTS, kind("Error: user already exists"))
    }

    @Test fun `every failure is worded for the profile being made, never Profile B`() {
        ProfileSetupFailureKind.values().forEach { kind ->
            val plain = ProfileFailureClassifier.describe(kind, null, null)
            assertFalse(kind.name, plain.compactMessage().contains("Profile B"))
            val named = ProfileFailureClassifier.describe(kind, "Android said this", null).named("Profile C")
            assertFalse(kind.name, named.compactMessage().contains("Profile B"))
            assertEquals("Android said this", named.platformMessage)
        }
        assertEquals("Profile C couldn't be added",
            ProfileFailureClassifier.local(ProfileSetupFailureKind.PROFILE_CREATION_REJECTED).named("Profile C").headline)
        assertEquals("No room for Profile C", ProfileFailureClassifier.local(ProfileSetupFailureKind.USER_TYPE_LIMIT).named("Profile C").headline)
        assertEquals("The new profile couldn't be added", ProfileFailureClassifier.local(ProfileSetupFailureKind.PROFILE_CREATION_REJECTED).headline)
    }

    // W3: room, counted as Android counts ------------------------------------------------------------------------------

    private fun user(id: Int, name: String, type: String, partial: Boolean = false, parent: Int? = null) =
        ProfileUserRecord(id, name, type.contains(".profile."), type.endsWith("profile.MANAGED"), parent, true, partial, type)

    private val b = "Cyclone_aaaaaaaaaaaaaaaa"
    private val trashed = "Cyclone_bbbbbbbbbbbbbbbb"
    private val unfinished = "Cyclone_cccccccccccccccc"
    private val users = listOf(
        user(0, "Owner", "android.os.usertype.full.SYSTEM"),
        user(10, b, "android.os.usertype.full.SECONDARY"),
        user(11, trashed, "android.os.usertype.full.SECONDARY"),
        user(12, "Work", "android.os.usertype.profile.MANAGED", parent = 0),
        user(13, "Private", "android.os.usertype.profile.PRIVATE", parent = 0),
        user(14, "Guest", "android.os.usertype.full.GUEST"),
        user(15, unfinished, "android.os.usertype.full.SECONDARY", partial = true),
    )
    private val records = listOf(
        CycloneProfileRecord(b, "Profile B", 10, 0, true, setOf("com.android.chrome"), "READY", true),
        CycloneProfileRecord(trashed, "Old test", 11, 0, true, emptySet(), "READY", true, removedAtMs = 1_000L),
    )

    @Test fun `room counts every alive user but guests, and names what uses each place`() {
        val room = ProfileCapacity.room(users, 6, records)
        assertEquals(6, room.used)
        assertEquals(0, room.free)
        assertEquals(ProfileCapacity.Verdict.TOTAL_LIMIT, room.verdict)
        assertEquals("Main · Profile B · Old test (Recently deleted, still uses a place) · Work profile · Private space · Unfinished Cyclone profile (unfinished)",
            room.line())
        assertEquals(ProfileCapacity.Verdict.ROOM, ProfileCapacity.room(users, 8, records).verdict)
        assertEquals(ProfileCapacity.Verdict.UNKNOWN, ProfileCapacity.room(users, null, records).verdict)
    }

    @Test fun `the type limit from dumpsys user wins over the total`() {
        val dumpsys = """
            User types version: 0
            mUserTypes:
              mName: android.os.usertype.full.SYSTEM
                mBaseType: FULL|SYSTEM
                mEnabled: true
                mMaxAllowed: -1 mMaxAllowedPerParent: -1
              mName: android.os.usertype.full.SECONDARY
                mBaseType: FULL
                mEnabled: true
                mMaxAllowed: 3 mMaxAllowedPerParent: -1
              mName: android.os.usertype.profile.MANAGED
                mEnabled: true
                mMaxAllowed: -1 mMaxAllowedPerParent: 1
        """.trimIndent()
        val types = ProfileCapacity.typeLimits(dumpsys)
        assertEquals(ProfileCapacity.TypeLimit(ProfileCapacity.FULL_SECONDARY, true, 3), types.single { it.type == ProfileCapacity.FULL_SECONDARY })
        val room = ProfileCapacity.room(users, 16, records, types)
        assertEquals(3, room.fullSecondary)
        assertEquals(ProfileCapacity.Verdict.TYPE_LIMIT, room.verdict)
        val disabled = ProfileCapacity.room(users, 16, records, listOf(ProfileCapacity.TypeLimit(ProfileCapacity.FULL_SECONDARY, false, -1)))
        assertEquals(ProfileCapacity.Verdict.TYPE_DISABLED, disabled.verdict)
        assertEquals(ProfileCapacity.Verdict.TYPE_LIMIT, ProfileCapacity.verdictFor(ProfileSetupFailureKind.USER_TYPE_LIMIT, null))
        assertEquals(ProfileCapacity.Verdict.TOTAL_LIMIT, ProfileCapacity.verdictFor(ProfileSetupFailureKind.MAX_USERS_REACHED, null))
    }

    @Test fun `the pre-check counts profiles too, as Android does`() {
        val owner = user(0, "Owner", "android.os.usertype.full.SYSTEM")
        val work = user(11, "Work", "android.os.usertype.profile.MANAGED", parent = 0)
        val second = user(12, b, "android.os.usertype.full.SECONDARY")
        val third = user(13, trashed, "android.os.usertype.full.SECONDARY")
        assertNull(SecondaryUserProvisioningPolicy.failure(0, 0, listOf(owner, work), 4))
        assertEquals(ProfileSetupFailureKind.MAX_USERS_REACHED,
            SecondaryUserProvisioningPolicy.failure(0, 0, listOf(owner, work, second, third), 4)?.kind)
        // Guests don't count.
        assertNull(SecondaryUserProvisioningPolicy.failure(0, 0, listOf(owner, work, second, user(14, "Guest", "android.os.usertype.full.GUEST")), 4))
    }

    // W4: clean-up only touches what Cyclone made and never finished --------------------------------------------------

    @Test fun `clean up lists only unfinished Cyclone users`() {
        assertEquals(listOf(15), ProfileTrash.unfinished(users, records, 0, 0).map { it.id })
        // Never the current user, even when unfinished.
        assertEquals(emptyList<Int>(), ProfileTrash.unfinished(users, records, 0, 15).map { it.id })
        // A created but not-ready Cyclone profile counts as unfinished; a ready one never does.
        val notReady = records + CycloneProfileRecord(unfinished, "Profile C", 15, 0, true, emptySet(), "PROFILE_CREATED", false)
        val finishedUsers = users.map { if (it.id == 15) it.copy(partial = false) else it }
        assertEquals(listOf(15), ProfileTrash.unfinished(finishedUsers, notReady, 0, 0).map { it.id })
        assertEquals(emptyList<Int>(), ProfileTrash.unfinished(users, records, null, 0).map { it.id })
    }

    // W5: profile room -------------------------------------------------------------------------------------------------

    @Test fun `the root manager is found from data adb`() {
        assertEquals(ProfileSetupPlan.RootManager.MAGISK, ProfileRoom.detect("magisk\nmodules\nservice.d\n"))
        assertEquals(ProfileSetupPlan.RootManager.KERNELSU, ProfileRoom.detect("ksu\nksud\nmodules"))
        assertEquals(ProfileSetupPlan.RootManager.APATCH, ProfileRoom.detect("ap\napd\nmodules"))
        assertNull(ProfileRoom.detect("modules\n"))
    }

    @Test fun `limits offered are above what is in use, default eight`() {
        assertEquals(listOf(6, 8, 12, 16), ProfileRoom.choices(5))
        assertEquals(listOf(12, 16), ProfileRoom.choices(8))
        assertEquals(8, ProfileRoom.defaultChoice(5))
        assertEquals(12, ProfileRoom.defaultChoice(9))
        assertNull(ProfileRoom.defaultChoice(16))
    }

    @Test fun `the module files are exactly what Cyclone writes`() {
        assertEquals("fw.max_users=8\n", ProfileRoom.systemProp(8))
        val prop = ProfileRoom.moduleProp("5.0.0-alpha.118.dev1", 8)
        assertTrue(prop.startsWith("id=cyclone_profiles\nname=Cyclone profiles\nversion=5.0.0-alpha.118.dev1\nversionCode=1\nauthor=Cyclone\n"))
        assertTrue(prop.contains("up to 8 profiles"))
        assertEquals("id=cyclone_profiles", ProfileRoom.moduleProp("bad;\nversion", 8).lineSequence().first())
        assertFalse(ProfileRoom.moduleProp("x\nid=evil", 8).contains("\nid=evil"))
        assertEquals(8, ProfileRoom.readModuleLimit("fw.max_users=8\n"))
        assertNull(ProfileRoom.readModuleLimit(null))
        assertThrows(IllegalArgumentException::class.java) { ProfileRoom.systemProp(64) }
    }

    @Test fun `allow is not offered for a per-type limit or without a root manager`() {
        val full = ProfileCapacity.room(users, 6, records)
        assertNull(ProfileRoom.refusal(full, ProfileSetupPlan.RootManager.MAGISK))
        assertNotNull(ProfileRoom.refusal(full, null))
        val typed = ProfileCapacity.room(users, 16, records, listOf(ProfileCapacity.TypeLimit(ProfileCapacity.FULL_SECONDARY, true, 3)))
        assertNotNull(ProfileRoom.refusal(typed, ProfileSetupPlan.RootManager.MAGISK))
    }

    @Test fun `room commands have one exact shape each`() {
        assertEquals("magisk resetprop fw.max_users 8", ProfileSetupPlan.shell(ProfileSetupPlan.roomSetProp(ProfileSetupPlan.RootManager.MAGISK, 8)))
        assertEquals("/data/adb/ksu/bin/resetprop fw.max_users 16",
            ProfileSetupPlan.shell(ProfileSetupPlan.roomSetProp(ProfileSetupPlan.RootManager.KERNELSU, 16)))
        assertEquals("magisk resetprop --delete fw.max_users", ProfileSetupPlan.shell(ProfileSetupPlan.roomResetProp(ProfileSetupPlan.RootManager.MAGISK, null)))
        assertEquals("/data/adb/ap/bin/resetprop fw.max_users 4",
            ProfileSetupPlan.shell(ProfileSetupPlan.roomResetProp(ProfileSetupPlan.RootManager.APATCH, 4)))
        assertThrows(IllegalArgumentException::class.java) { ProfileSetupPlan.roomSetProp(ProfileSetupPlan.RootManager.MAGISK, 17) }
        assertThrows(IllegalArgumentException::class.java) { ProfileSetupPlan.roomStageModule("/data/local/tmp/evil") }
        val source = "/data/user/0/com.cyclone.mobile/files/profile-room/cyclone_profiles"
        assertEquals("/system/bin/cp -r $source /data/adb/modules/cyclone_profiles.new", ProfileSetupPlan.shell(ProfileSetupPlan.roomStageModule(source)))
        assertEquals("/system/bin/rm -rf /data/adb/modules/cyclone_profiles", ProfileSetupPlan.shell(ProfileSetupPlan.roomClearModule(false)))
        assertEquals("/system/bin/mv /data/adb/modules/cyclone_profiles.new /data/adb/modules/cyclone_profiles",
            ProfileSetupPlan.shell(ProfileSetupPlan.roomPlaceModule()))
        assertEquals("/system/bin/dumpsys user", ProfileSetupPlan.shell(ProfileSetupPlan.dumpUsers()))
        assertThrows(IllegalArgumentException::class.java) { ProfileSetupPlan.roomReadModule("../../etc/passwd") }
        listOf(ProfileSetupPlan.roomListAdb(), ProfileSetupPlan.roomReadProp(), ProfileSetupPlan.roomOwnModule(), ProfileSetupPlan.roomLabelModule(),
            ProfileSetupPlan.roomListModule(), ProfileSetupPlan.roomReadModule("system.prop"), ProfileSetupPlan.roomClearModule(true))
            .forEach { assertTrue(it.operation.name, ProfileSetupPlan.acceptsOnlyTyped(it)) }
    }

    // W1/W8: the journal and the debug file never keep a secret ---------------------------------------------------------

    @Test fun `the step journal keeps the newest two hundred, redacted`() {
        val ring = ArrayDeque<ProfileStep>()
        repeat(205) { ProfileStepJournal.add(ring, ProfileStep(it.toLong(), "LIST_USERS", "cmd", 0, 1, "ok", null)) }
        assertEquals(200, ring.size)
        assertEquals(5L, ring.first().atMs)
        assertEquals(ring.toList(), ProfileStepJournal.decode(ProfileStepJournal.encode(ring.toList())))
        val red = ProfileDebugRedaction.text("token=abc123 api_key=sk-ABCDEFGHIJKLMNOP password: hunter2 " + "A".repeat(60))
        listOf("abc123", "sk-ABCDEFGHIJKLMNOP", "hunter2", "A".repeat(60)).forEach { assertFalse(it, red.contains(it)) }
        assertTrue(ProfileDebugRedaction.text("Error: Cannot add user. Maximum user limit is reached.").contains("Maximum user limit"))
    }

    private fun facts(): ProfileDebugReport.Facts = ProfileDebugReport.Facts(
        atMs = 1_790_000_000_000L,
        app = mapOf("versionName" to "5.0.0-alpha.118.dev1", "versionCode" to 272L, "manufacturer" to "Google", "model" to "Pixel 8",
            "release" to "15", "sdk" to 35),
        rootManager = "MAGISK",
        room = ProfileCapacity.room(users, 6, records),
        roomStatus = ProfileRoom.Status(ProfileSetupPlan.RootManager.MAGISK, null, 6, null, false, false),
        usersRaw = "0: id=0, name=Owner, type=android.os.usertype.full.SYSTEM",
        records = records.map { it.copy(ext = mapOf("cyclone-cloak" to """{"cloakProfileId":"secret-binding-hunter2"}""")) },
        journal = mapOf("name" to unfinished, "label" to "Profile C", "stage" to "PLANNED"),
        steps = listOf(ProfileStep(1L, "CREATE_SECONDARY_USER", "/system/bin/pm create-user $unfinished", 1, 120,
            "Error: Cannot add more users of type android.os.usertype.full.SECONDARY. Maximum number of that type already exists. password: hunter2 api_key=sk-ABCDEFGHIJKLMNOP",
            "USER_TYPE_LIMIT")),
        connectors = listOf(mapOf("id" to "cyclone-cloak", "package" to "com.cyclone.cloak", "cert" to "ab12cd34ef56")),
        carry = null,
        failure = ProfileFailureClassifier.local(ProfileSetupFailureKind.USER_TYPE_LIMIT, "Maximum number of that type already exists").named("Profile C"),
    )

    @Test fun `the debug file holds the facts and none of the secrets`() {
        val f = facts()
        val json = ProfileDebugReport.json(f)
        val text = json.toString()
        assertEquals(ProfileDebugReport.SCHEMA, json.getString("schema"))
        listOf("hunter2", "sk-ABCDEFGHIJKLMNOP", "secret-binding").forEach { assertFalse(it, text.contains(it)) }
        assertEquals("cyclone-cloak", json.getJSONArray("registry").getJSONObject(0).getJSONArray("extConnectors").getString(0))
        assertEquals("TOTAL_LIMIT", json.getJSONObject("users").getString("verdict"))
        assertEquals("USER_TYPE_LIMIT", json.getJSONObject("error").getString("kind"))
        assertTrue(json.getJSONArray("steps").getJSONObject(0).getString("output").contains("Maximum number of that type"))
        val summary = ProfileDebugReport.summary(f)
        assertTrue(summary.contains("Problem: No room for Profile C"))
        assertTrue(summary.contains("Room: 6 of 6 places in use"))
        assertFalse(summary.contains("hunter2"))
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(ProfileDebugReport.zip(f))).use { zip ->
            while (true) { val e = zip.nextEntry ?: break; names += e.name; if (e.name == "debug.json") JSONObject(zip.readBytes().toString(Charsets.UTF_8)) }
        }
        assertEquals(listOf("debug.json", "summary.txt"), names)
        assertTrue(ProfileDebugReport.fileName(f.atMs).matches(Regex("cyclone-profile-debug-\\d{8}-\\d{6}\\.zip")))
    }

    // W6/W9: every failure has its screen ---------------------------------------------------------------------------

    @Test fun `room failures offer the right fixes in order`() {
        val room = ProfileCapacity.room(users, 6, records)
        assertEquals(listOf(ProfileProblem.Fix.DELETE_REMOVED, ProfileProblem.Fix.CLEAN_UP, ProfileProblem.Fix.ALLOW_MORE,
            ProfileProblem.Fix.ANDROID_USERS, ProfileProblem.Fix.RETRY), ProfileProblem.fixes(ProfileSetupFailureKind.MAX_USERS_REACHED, room, null))
        assertFalse(ProfileProblem.Fix.ALLOW_MORE in ProfileProblem.fixes(ProfileSetupFailureKind.USER_TYPE_LIMIT, room, null))
        assertFalse(ProfileProblem.Fix.ALLOW_MORE in ProfileProblem.fixes(ProfileSetupFailureKind.MAX_USERS_REACHED, room, "Needs Magisk"))
        assertTrue(ProfileProblem.Fix.ROOT_MANAGER in ProfileProblem.fixes(ProfileSetupFailureKind.ROOT_DENIED, null, null))
        ProfileSetupFailureKind.values().forEach { ProfileProblem.fixes(it, room, null) } // none throws
        val checks = ProfileProblem.checks(ProfileSetupFailureKind.MAX_USERS_REACHED, room, "Magisk")
        assertEquals(true to "Root works (Magisk)", checks.first())
        assertTrue(checks.contains(false to "Places on the phone: 6 of 6 in use"))
        assertTrue(ProfileProblem.allowText(8, 6, 2, "MAGISK").contains("small Magisk module"))
        assertTrue(ProfileProblem.allowText(8, 6, 2, "MAGISK").contains("Storage is low"))
    }

    @Test fun `a suggested name is never one already used`() {
        assertEquals("Profile B", ProfileProblem.suggestName(emptyList()))
        assertEquals("Profile D", ProfileProblem.suggestName(listOf("Profile B", "profile c ")))
        assertEquals("Profile C", ProfileProblem.suggestName(listOf("Profile B", "Old test")))
    }
}
