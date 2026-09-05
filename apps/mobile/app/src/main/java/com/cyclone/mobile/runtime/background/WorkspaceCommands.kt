package com.cyclone.mobile.runtime.background

/** Only typed display-targeted input can reach the privileged process. No shell source strings. */
object WorkspaceCommands {
    const val TAP = 1
    const val SWIPE = 2
    const val BACK = 3
    const val TEXT = 4
    fun input(displayId: Int, kind: Int, coordinates: FloatArray, text: String): List<String> {
        require(displayId > 0) { "Background input requires a nonzero display" }
        require(coordinates.all { it.isFinite() && it >= 0 })
        val prefix = listOf("/system/bin/input", "-d", displayId.toString())
        return prefix + when (kind) {
            TAP -> { require(coordinates.size == 2); listOf("tap") + coordinates.map { it.toString() } }
            SWIPE -> { require(coordinates.size == 5 && coordinates[4] in 100f..3000f); listOf("swipe") + coordinates.take(4).map { it.toString() } + coordinates[4].toInt().toString() }
            BACK -> { require(coordinates.isEmpty()); listOf("keyevent", "KEYCODE_BACK") }
            TEXT -> {
                require(coordinates.isEmpty())
                require(text.isNotEmpty() && text.length <= 500 && text.all { it in ' '..'~' && it != '%' }) {
                    "UNSUPPORTED: input text must be printable ASCII without percent escapes"
                }
                listOf("text", text.replace(" ", "%s"))
            }
            else -> throw IllegalArgumentException("UNSUPPORTED input operation")
        }
    }

    data class Task(val rootTaskId: Int, val taskId: Int, val displayId: Int, val packageName: String)
    /** Fail closed when an OEM changes the shell output instead of guessing task ownership. */
    fun tasks(output: String): List<Task> {
        val tasks = mutableListOf<Task>()
        var root: Int? = null
        var display: Int? = null
        for (line in output.lineSequence()) {
            Regex("(?:RootTask|Stack) id=(\\d+).*displayId=(\\d+)").find(line)?.let {
                root = it.groupValues[1].toInt(); display = it.groupValues[2].toInt()
            }
            val match = Regex("taskId=(\\d+): ([A-Za-z0-9_.]+)/(?:[^ ]+)").find(line)
            if (match != null && root != null && display != null) {
                tasks += Task(root!!, match.groupValues[1].toInt(), display!!, match.groupValues[2])
            }
        }
        return tasks
    }
}
