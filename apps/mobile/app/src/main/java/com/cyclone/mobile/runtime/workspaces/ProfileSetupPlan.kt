package com.cyclone.mobile.runtime.workspaces

enum class ProfileSetupOperation {
    VERIFY_ROOT,
    CURRENT_USER,
    LIST_USERS,
    GET_MAX_USERS,
    CREATE_MANAGED_PROFILE,
    CREATE_SECONDARY_USER,
    SWITCH_USER,
    START_PROFILE,
    PROFILE_STATE,
    INSTALL_EXISTING_PACKAGE,
    LIST_PROFILE_PACKAGE,
    MARK_SETUP_COMPLETE,
    READ_SETUP_COMPLETE,
}

/**
 * A closed profile-provisioning command. There is deliberately no public constructor that accepts
 * executable or shell text; every instance comes from one of the fixed factories below.
 */
class ProfileSetupCommand private constructor(
    val operation: ProfileSetupOperation,
    private val argv: List<String>,
) {
    internal fun tokens(): List<String> = argv.toList()

    companion object {
        internal fun fixed(operation: ProfileSetupOperation, vararg argv: String): ProfileSetupCommand =
            ProfileSetupCommand(operation, argv.toList())
    }
}

/** Fixed profile provisioning verbs only. No arbitrary root/shell surface. */
object ProfileSetupPlan {
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    private val namePattern = Regex("Cyclone_[a-f0-9]{16}")
    private val shellTokenPattern = Regex("[A-Za-z0-9_./:-]+")

    fun validProfileName(name: String): Boolean = name.matches(namePattern)
    fun validPackageName(packageName: String): Boolean = packageName.matches(packagePattern)

    fun verifyRoot(): ProfileSetupCommand = ProfileSetupCommand.fixed(
        ProfileSetupOperation.VERIFY_ROOT,
        "/system/bin/id",
    )

    fun currentUser(): ProfileSetupCommand = ProfileSetupCommand.fixed(
        ProfileSetupOperation.CURRENT_USER,
        "/system/bin/am", "get-current-user",
    )

    fun listUsers(): ProfileSetupCommand = ProfileSetupCommand.fixed(
        ProfileSetupOperation.LIST_USERS,
        "/system/bin/cmd", "user", "list", "--all", "--verbose",
    )

    fun getMaxUsers(): ProfileSetupCommand = ProfileSetupCommand.fixed(
        ProfileSetupOperation.GET_MAX_USERS,
        "/system/bin/pm", "get-max-users",
    )

    fun createManagedProfile(parentUserId: Int, name: String): ProfileSetupCommand {
        require(parentUserId >= 0 && validProfileName(name))
        return ProfileSetupCommand.fixed(
            ProfileSetupOperation.CREATE_MANAGED_PROFILE,
            "/system/bin/pm", "create-user", "--profileOf", parentUserId.toString(), "--managed", name,
        )
    }

    fun createSecondaryUser(name: String): ProfileSetupCommand {
        require(validProfileName(name))
        return ProfileSetupCommand.fixed(ProfileSetupOperation.CREATE_SECONDARY_USER,
            "/system/bin/pm", "create-user", name)
    }

    fun switchUser(userId: Int): ProfileSetupCommand {
        require(userId >= 0)
        return ProfileSetupCommand.fixed(ProfileSetupOperation.SWITCH_USER,
            "/system/bin/am", "switch-user", userId.toString())
    }

    fun startProfile(userId: Int): ProfileSetupCommand {
        require(userId > 0)
        return ProfileSetupCommand.fixed(
            ProfileSetupOperation.START_PROFILE,
            "/system/bin/am", "start-user", "-w", userId.toString(),
        )
    }

    fun profileState(userId: Int): ProfileSetupCommand {
        require(userId > 0)
        return ProfileSetupCommand.fixed(
            ProfileSetupOperation.PROFILE_STATE,
            "/system/bin/am", "get-started-user-state", userId.toString(),
        )
    }

    fun installExisting(userId: Int, packageName: String): ProfileSetupCommand {
        require(userId > 0 && validPackageName(packageName))
        return ProfileSetupCommand.fixed(
            ProfileSetupOperation.INSTALL_EXISTING_PACKAGE,
            "/system/bin/cmd", "package", "install-existing", "--user", userId.toString(), packageName,
        )
    }

    fun listProfilePackage(userId: Int, packageName: String): ProfileSetupCommand {
        require(userId > 0 && validPackageName(packageName))
        return ProfileSetupCommand.fixed(
            ProfileSetupOperation.LIST_PROFILE_PACKAGE,
            "/system/bin/pm", "list", "packages", "--user", userId.toString(), packageName,
        )
    }

    fun markSetupComplete(userId: Int): ProfileSetupCommand {
        require(userId > 0)
        return ProfileSetupCommand.fixed(
            ProfileSetupOperation.MARK_SETUP_COMPLETE,
            "/system/bin/settings", "--user", userId.toString(), "put", "secure", "user_setup_complete", "1",
        )
    }

    fun readSetupComplete(userId: Int): ProfileSetupCommand {
        require(userId > 0)
        return ProfileSetupCommand.fixed(
            ProfileSetupOperation.READ_SETUP_COMPLETE,
            "/system/bin/settings", "--user", userId.toString(), "get", "secure", "user_setup_complete",
        )
    }

    internal fun shell(command: ProfileSetupCommand): String {
        val tokens = command.tokens()
        check(tokens.isNotEmpty() && tokens.all { it.matches(shellTokenPattern) })
        check(isExpectedShape(command.operation, tokens))
        return tokens.joinToString(" ")
    }

    /** Test seam proving that a caller cannot smuggle arbitrary shell data into the executor. */
    internal fun acceptsOnlyTyped(command: ProfileSetupCommand): Boolean = runCatching { shell(command) }.isSuccess

    private fun isExpectedShape(operation: ProfileSetupOperation, tokens: List<String>): Boolean = when (operation) {
        ProfileSetupOperation.VERIFY_ROOT -> tokens == listOf("/system/bin/id")
        ProfileSetupOperation.CURRENT_USER -> tokens == listOf("/system/bin/am", "get-current-user")
        ProfileSetupOperation.LIST_USERS -> tokens == listOf("/system/bin/cmd", "user", "list", "--all", "--verbose")
        ProfileSetupOperation.GET_MAX_USERS -> tokens == listOf("/system/bin/pm", "get-max-users")
        ProfileSetupOperation.CREATE_MANAGED_PROFILE -> tokens.size == 6 && tokens[0] == "/system/bin/pm" &&
            tokens[1] == "create-user" && tokens[2] == "--profileOf" && tokens[3].toIntOrNull()?.let { it >= 0 } == true &&
            tokens[4] == "--managed" && validProfileName(tokens[5])
        ProfileSetupOperation.CREATE_SECONDARY_USER -> tokens.size == 3 &&
            tokens.take(2) == listOf("/system/bin/pm", "create-user") && validProfileName(tokens[2])
        ProfileSetupOperation.SWITCH_USER -> tokens.size == 3 &&
            tokens.take(2) == listOf("/system/bin/am", "switch-user") && tokens[2].toIntOrNull()?.let { it >= 0 } == true
        ProfileSetupOperation.START_PROFILE -> tokens.size == 4 && tokens[0] == "/system/bin/am" &&
            tokens[1] == "start-user" && tokens[2] == "-w" && tokens[3].toIntOrNull()?.let { it > 0 } == true
        ProfileSetupOperation.PROFILE_STATE -> tokens.size == 3 && tokens[0] == "/system/bin/am" &&
            tokens[1] == "get-started-user-state" && tokens[2].toIntOrNull()?.let { it > 0 } == true
        ProfileSetupOperation.INSTALL_EXISTING_PACKAGE -> tokens.size == 6 && tokens[0] == "/system/bin/cmd" &&
            tokens[1] == "package" && tokens[2] == "install-existing" && tokens[3] == "--user" &&
            tokens[4].toIntOrNull()?.let { it > 0 } == true && validPackageName(tokens[5])
        ProfileSetupOperation.LIST_PROFILE_PACKAGE -> tokens.size == 6 && tokens[0] == "/system/bin/pm" &&
            tokens[1] == "list" && tokens[2] == "packages" && tokens[3] == "--user" &&
            tokens[4].toIntOrNull()?.let { it > 0 } == true && validPackageName(tokens[5])
        ProfileSetupOperation.MARK_SETUP_COMPLETE -> tokens.size == 7 && tokens[0] == "/system/bin/settings" &&
            tokens[1] == "--user" && tokens[2].toIntOrNull()?.let { it > 0 } == true &&
            tokens.drop(3) == listOf("put", "secure", "user_setup_complete", "1")
        ProfileSetupOperation.READ_SETUP_COMPLETE -> tokens.size == 6 && tokens[0] == "/system/bin/settings" &&
            tokens[1] == "--user" && tokens[2].toIntOrNull()?.let { it > 0 } == true &&
            tokens.drop(3) == listOf("get", "secure", "user_setup_complete")
    }
}

data class ProfileUserRecord(
    val id: Int,
    val name: String,
    val profile: Boolean,
    val managed: Boolean,
    val parentId: Int?,
    val running: Boolean,
    val partial: Boolean,
    val userType: String = "",
)

object ProfileSetupParser {
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    internal fun users(output: String): List<ProfileUserRecord> = output.lineSequence().mapNotNull { line ->
        val id = Regex("\\bid=([0-9]+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
        val name = Regex("\\bname=(.*?),\\s*type=").find(line)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
        val type = Regex("\\btype=([^,]+)").find(line)?.groupValues?.get(1)?.trim().orEmpty()
        val flags = Regex("\\bflags=([^\\s(]+)").find(line)?.groupValues?.get(1)?.split('|').orEmpty()
        val parentId = Regex("\\(parentId=([0-9]+)\\)").find(line)?.groupValues?.get(1)?.toIntOrNull()
        ProfileUserRecord(
            id = id,
            name = name,
            profile = flags.any { it.equals("PROFILE", true) } || type.contains(".profile.", ignoreCase = true),
            managed = type.endsWith("profile.MANAGED", ignoreCase = true) || type.equals("profile.MANAGED", ignoreCase = true),
            parentId = parentId,
            running = line.contains("(running)", ignoreCase = true),
            partial = line.contains("(partial)", ignoreCase = true),
            userType = type,
        )
    }.toList()

    fun mainUserId(users: List<ProfileUserRecord>): Int? {
        val system = users.filter { !it.profile && !it.partial && it.userType.endsWith("full.SYSTEM", ignoreCase = true) }
        if (system.size == 1) return system.single().id
        return users.singleOrNull { it.id == 0 && !it.profile && !it.partial }?.id
    }

    fun currentUserId(output: String): Int? = output.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.matches(Regex("[0-9]+")) }
        ?.toIntOrNull()

    fun maxUsers(output: String): Int? = Regex("(?i)maximum supported users:\\s*([0-9]+)")
        .find(output)?.groupValues?.get(1)?.toIntOrNull()

    fun createdUser(output: String): Int? = Regex("(?i)success:\\s*created user id\\s+([0-9]+)")
        .find(output)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

    fun packages(output: String): Set<String> = output.lineSequence()
        .filter { it.startsWith("package:") }
        .map { it.removePrefix("package:").trim() }
        .filter { it.matches(packagePattern) }
        .toSet()

    internal fun ownedUser(users: List<ProfileUserRecord>, name: String, parentUserId: Int): ProfileUserRecord? {
        require(ProfileSetupPlan.validProfileName(name))
        return users.singleOrNull {
            it.id > 0 && it.id != parentUserId && it.name == name && it.managed && it.parentId == parentUserId && !it.partial
        }
    }
}
