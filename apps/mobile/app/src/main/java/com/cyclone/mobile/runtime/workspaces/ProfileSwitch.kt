package com.cyclone.mobile.runtime.workspaces

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Plan 57 P1 (alpha.119): the profile switch as a state machine with a journal, so a switch either ends where the owner
 * asked or comes back by itself, and every stage is in the debug file.
 *
 * 1. **Preflight**: nothing changes; root, identities, unlocked, Cyclone in the target, its root proven.
 * 2. **Prepare and carry**: as before, each step read back.
 * 3. **Arm the return**: a fixed root-side timer switches back to where the owner was if the target's Cyclone hasn't
 *    said hello within [RETURN_AFTER_S] (and only while the target is still in front).
 * 4. **Switch**, waiting for Android adaptively ([waitSchedule], about 27 s at most).
 * 5. **Confirm**: the target's Cyclone says hello (which disarms the return) and tells its connectors.
 */
object ProfileSwitch {
    const val RETURN_AFTER_S = 45
    const val KEEP = 20

    enum class Stage { PREFLIGHT, PREPARE, CARRY, ARM_RETURN, SWITCH, CONFIRM }
    enum class Outcome { DONE, CONFIRM_LATE, FAILED, NOT_STARTED }

    data class StageResult(val stage: Stage, val ok: Boolean, val ms: Long, val note: String? = null)

    data class Record(
        val atMs: Long,
        val from: Int,
        val to: Int,
        val label: String,
        val stages: List<StageResult>,
        val outcome: Outcome,
        val note: String? = null,
    ) {
        fun toJson(): JSONObject = JSONObject().put("at", atMs).put("from", from).put("to", to).put("label", label)
            .put("outcome", outcome.name).put("note", note ?: JSONObject.NULL)
            .put("stages", JSONArray(stages.map {
                JSONObject().put("stage", it.stage.name).put("ok", it.ok).put("ms", it.ms).put("note", it.note ?: JSONObject.NULL)
            }))

        companion object {
            fun fromJson(o: JSONObject): Record = Record(
                o.optLong("at"), o.optInt("from"), o.optInt("to"), o.optString("label"),
                o.optJSONArray("stages")?.let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        val s = a.optJSONObject(i) ?: return@mapNotNull null
                        val stage = runCatching { Stage.valueOf(s.optString("stage")) }.getOrNull() ?: return@mapNotNull null
                        StageResult(stage, s.optBoolean("ok"), s.optLong("ms"), if (s.isNull("note")) null else s.optString("note"))
                    }
                }.orEmpty(),
                runCatching { Outcome.valueOf(o.optString("outcome")) }.getOrDefault(Outcome.FAILED),
                if (o.isNull("note")) null else o.optString("note"),
            )
        }
    }

    /** Collects stage results while a switch runs. */
    class Tracker(private val clock: () -> Long = System::currentTimeMillis) {
        val results = mutableListOf<StageResult>()
        fun <T> stage(stage: Stage, block: () -> T): T {
            val start = clock()
            return try {
                block().also { results += StageResult(stage, true, clock() - start) }
            } catch (error: Throwable) {
                results += StageResult(stage, false, clock() - start, ProfileDebugRedaction.text(error.message.orEmpty(), 200))
                throw error
            }
        }
        fun note(stage: Stage, ok: Boolean, note: String?) { results += StageResult(stage, ok, 0, note) }
    }

    // Pure ---------------------------------------------------------------------------------------------------------

    /** How long to wait between checks that Android finished switching: quick at first, then patient. */
    fun waitSchedule(): List<Long> = List(10) { 150L } + List(10) { 300L } + List(22) { 1_000L }

    private val nonce = Regex("[a-f0-9]{32}")
    fun validNonce(value: String): Boolean = value.matches(nonce)

    /** Where the target's Cyclone says hello for one switch (its own device-protected files). */
    fun helloPath(target: Int, nonce: String): String {
        require(target >= 0 && validNonce(nonce))
        return "/data/user_de/$target/com.cyclone.mobile/files/switch-hello-$nonce"
    }

    /**
     * The fixed root-side return: after [seconds], when [target] is still in front and its Cyclone never said hello,
     * switch back to [source]. Built from numbers and a hex nonce only.
     */
    fun returnCommand(source: Int, target: Int, nonce: String, seconds: Int = RETURN_AFTER_S): String {
        require(source >= 0 && target >= 0 && source != target && validNonce(nonce) && seconds in 10..120)
        val hello = helloPath(target, nonce)
        return "setsid sh -c 'sleep $seconds; " +
            "[ \"\$(/system/bin/am get-current-user)\" = \"$target\" ] && [ ! -f $hello ] && /system/bin/am switch-user $source' " +
            "</dev/null >/dev/null 2>&1 &"
    }

    /** The words the owner gets when a root manager needs one step done by hand. */
    fun rootGuidance(manager: ProfileSetupPlan.RootManager?, label: String): String = when (manager) {
        ProfileSetupPlan.RootManager.KERNELSU ->
            "Open KernelSU → Superuser and allow Cyclone in $label (KernelSU asks per profile). Then switch again."
        ProfileSetupPlan.RootManager.APATCH ->
            "Open APatch → Superuser and allow Cyclone in $label. Then switch again."
        ProfileSetupPlan.RootManager.MAGISK ->
            "Open Magisk → Superuser and allow Cyclone in $label, and set Magisk → Settings → Multiuser mode to \"User-independent\". Then switch again."
        null -> "Cyclone can't use root in $label yet. Allow Cyclone in your root manager there, then switch again."
    }

    /**
     * Profiles a profile's Cyclone learns on a switch. The main profile is the authority: what it sends replaces the
     * list (its looks and connectors' data included). From another profile, only profiles this one doesn't know yet are
     * added; nothing is removed or renamed.
     */
    fun mergeRegistry(local: List<CycloneProfileRecord>, incoming: List<CycloneProfileRecord>, fromMain: Boolean): List<CycloneProfileRecord> =
        if (fromMain) incoming else local + incoming.filter { other -> local.none { it.id == other.id } }

    /** `settings get global user_switcher_enabled`: Android's own way back, on or off ("null" means the default, on). */
    fun userSwitcherOn(output: String?): Boolean? = when (output?.trim()) {
        "1", "null", "" -> true
        "0" -> false
        else -> null
    }

    // On the phone: the last switches, for Profiles and the debug file -------------------------------------------------

    private val records = ArrayDeque<Record>()
    @Volatile private var file: File? = null

    @Synchronized fun attach(context: Context) {
        val dir = File(context.noBackupFilesDir, "profile-debug").apply { if (!isDirectory) mkdirs() }
        file = File(dir, "switches.json")
        if (records.isEmpty()) {
            runCatching { decode(AtomicFile(file!!).openRead().use { it.readBytes().toString(Charsets.UTF_8) }) }
                .getOrDefault(emptyList()).takeLast(KEEP).forEach(records::addLast)
        }
    }

    @Synchronized fun remember(record: Record) {
        records.addLast(record)
        while (records.size > KEEP) records.removeFirst()
        val target = file ?: return
        runCatching {
            val atomic = AtomicFile(target)
            val out = atomic.startWrite()
            try { out.write(encode(records.toList()).toByteArray(Charsets.UTF_8)); atomic.finishWrite(out) }
            catch (e: Exception) { atomic.failWrite(out); throw e }
        }
    }

    @Synchronized fun recent(): List<Record> = records.toList()

    internal fun encode(list: List<Record>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

    internal fun decode(text: String): List<Record> = runCatching {
        val a = JSONArray(text)
        (0 until a.length()).map { Record.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())
}
