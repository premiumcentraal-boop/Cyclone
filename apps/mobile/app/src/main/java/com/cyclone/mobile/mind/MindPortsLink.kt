package com.cyclone.mobile.mind

import org.json.JSONObject

/** What a plugin answered for a run's wait (plan 48 run 4). A code is never here: only that it came and its length. */
data class MindPortAnswer(
    val state: String,
    val reason: String = "",
    val value: Any? = null,
    val url: String? = null,
    /** A file that reached the phone: its name and where it was saved. */
    val fileName: String? = null,
    val folder: String? = null,
    val codeLength: Int = 0,
    val plugin: String = "",
)

/**
 * Cyclone Ports for a running mission (plan 48 run 4): the Mind sends on out ports and waits on in ports through the
 * owner's PC. Present only when a PC's Port Hub is connected to this phone. Pure; the phone side is
 * `ports.PortOutboxLink`.
 */
interface MindPortsLink {
    /** Queues an out-port message; null when queued, else why not. [app] is the app the run is in now. */
    fun send(port: String, data: JSONObject, image: ByteArray?, app: String?): String?

    /** Blocks until a plugin answers, the time runs out or [cancelled] says the run stopped. */
    fun wait(port: String, ask: String, timeoutS: Int, place: String?, app: String?, cancelled: () -> Boolean): MindPortAnswer

    /** The app or site a code is for ("package:…" or "chrome:https://…"), as a code fill will check it; null when unknown. */
    fun place(page: com.cyclone.mobile.agent.contract.AgentPageCard): String?
}
