package com.cyclone.mobile.runtime.workspaces

/** Only fixed profile provisioning verbs. Never accepts shell source or user-supplied arguments. */
object ProfileSetupPlan {
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    private val namePattern = Regex("Cyclone_[a-f0-9]{16}")
    fun create(parent: Int, name: String): List<String> {
        require(parent >= 0 && name.matches(namePattern))
        return listOf("/system/bin/pm", "create-user", "--profileOf", parent.toString(), "--managed", name)
    }
    fun install(user: Int, pkg: String): List<String> {
        require(user > 0 && pkg.matches(packagePattern))
        return listOf("/system/bin/cmd", "package", "install-existing", "--user", user.toString(), pkg)
    }
    fun packages(output: String): Set<String> = output.lineSequence().filter { it.startsWith("package:") }
        .map { it.removePrefix("package:").trim() }.filter { it.matches(packagePattern) }.toSet()
    fun createdUser(output: String): Int? = Regex("Success: created user id ([0-9]+)").find(output)
        ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
    fun ownedUser(output: String, name: String): Int? {
        require(name.matches(namePattern))
        return Regex("UserInfo\\{([0-9]+):${Regex.escape(name)}:([a-fA-F0-9]+)\\}")
            .findAll(output).mapNotNull { match ->
                val id = match.groupValues[1].toIntOrNull()
                val flags = match.groupValues[2].toIntOrNull(16)
                id?.takeIf { it > 0 && flags != null && flags and 0x20 != 0 }
            }.toList().singleOrNull()
    }
    fun shell(args: List<String>): String {
        // These tokens come exclusively from typed internal plans, not a phone/MCP tool.
        require(args.isNotEmpty() && args.all { it.matches(Regex("[A-Za-z0-9_./:-]+")) })
        return args.joinToString(" ")
    }
}
