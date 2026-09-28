package com.cyclone.mobile.manual

import android.content.Context
import com.cyclone.mobile.ai.OpenRouterCatalogStore
import com.cyclone.mobile.ai.OpenRouterSecretStore
import com.cyclone.mobile.ai.ProviderCancellation
import com.cyclone.mobile.manual.dictionary.AppDictionary
import com.cyclone.mobile.manual.dictionary.DictionaryJson
import com.cyclone.mobile.manual.dictionary.Organizer
import com.cyclone.mobile.manual.dictionary.OrganizerDecision
import com.cyclone.mobile.manual.dictionary.OrganizerJudge
import com.cyclone.mobile.manual.dictionary.OrganizerPrompt
import com.cyclone.mobile.manual.dictionary.OrganizerQuestion
import com.cyclone.mobile.manual.dictionary.PassInfo
import com.cyclone.mobile.mind.MindModelRequest
import com.cyclone.mobile.mind.OpenRouterMindModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The phone side of the app dictionary (plan 36 §7). A mapping pass feeds every screen it reads through the
 * [StructureReader] (only the app's own words survive) into the organizer's record; when the pass ends the organizer
 * runs its gates and asks its one question with the model chosen for the pass. JEV watches that question and never
 * decides. Dictionaries are stored per app on the phone, with structure only.
 */
object ManualRuntime {
    /** The model the owner picked for a pass: the phone's current model, or one from its list. */
    const val PHONE_MODEL = "phone"

    private class Pass(val packageName: String, val versionName: String?, val modelChoice: String, val rooms: MutableSet<String> = ConcurrentHashMap.newKeySet())

    private val passes = ConcurrentHashMap<String, Pass>()
    private val choices = ConcurrentHashMap<String, String>()
    private val cache = ConcurrentHashMap<String, AppDictionary>()
    private val lexicons = ConcurrentHashMap<String, Pair<Long, AppLexicon>>()
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "cyclone-manual").apply { isDaemon = true } }

    /** Seam for tests and the Lab: who answers the organizer's question. */
    internal var judgeFactory: (Context, String, String) -> OrganizerJudge? = { context, appLabel, model -> modelJudge(context, appLabel, model) }

    // ---- pass lifecycle ----

    /** mapping.start: remembers which model decides for this pass ("phone" = the phone's current model). */
    fun configure(jobId: String, model: String?) {
        choices[jobId] = model?.takeIf { it.isNotBlank() } ?: PHONE_MODEL
    }

    /** Resolves "phone" to the phone's current model id, when the pass starts. */
    fun resolveModel(context: Context, choice: String): String =
        if (choice == PHONE_MODEL) OpenRouterCatalogStore.activeId(context) else OpenRouterCatalogStore.canonicalId(choice)

    /** One screen of a mapping pass. Never throws into the mapper. */
    internal fun observe(context: Context, jobId: String, placeId: String, roomKey: String, captured: com.cyclone.mobile.gateway.GatewayObservation) {
        runCatching {
            val packageName = placeId.removePrefix("package:").takeIf { placeId.startsWith("package:") && PACKAGE.matches(it) } ?: return
            if (captured.page.packageName != packageName) return
            val app = context.applicationContext
            val pass = passes.getOrPut(jobId) {
                val version = runCatching { app.packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
                Pass(packageName, version, resolveModel(app, choices[jobId] ?: PHONE_MODEL))
            }
            pass.rooms += roomKey
            val lexicon = lexicon(app, packageName)
            val label = runCatching { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(packageName, 0)).toString() }.getOrNull()
            val found = StructureReader(lexicon, label).read(roomKey, nodes(captured))
            if (found.proposals.isEmpty()) return
            synchronized(lock) {
                val dict = load(app, packageName)
                save(app, Organizer.record(dict, found.proposals, PassInfo(System.currentTimeMillis(), pass.versionName)))
            }
        }
    }

    /** The pass ended: the organizer runs in the background (gates, then at most one model question). */
    fun finish(context: Context, jobId: String) {
        val pass = passes.remove(jobId) ?: return
        choices.remove(jobId)
        val app = context.applicationContext
        worker.execute {
            runCatching {
                val label = appLabel(app, pass.packageName)
                val judge = judgeFactory(app, label, pass.modelChoice)
                val watched = if (judge == null) null else WatchedJudge(judge) { questions, decisions -> watchJev(app, pass.packageName, label, questions, decisions) }
                synchronized(lock) {
                    val result = Organizer.run(load(app, pass.packageName), watched, PassInfo(System.currentTimeMillis(), pass.versionName, pass.rooms.toSet()))
                    save(app, result.dictionary)
                }
            }
        }
    }

    // ---- reading and owner edits ----

    fun dictionary(context: Context, packageName: String): AppDictionary = synchronized(lock) { load(context.applicationContext, packageName) }

    fun edit(context: Context, packageName: String, edit: Organizer.OwnerEdit): AppDictionary = synchronized(lock) {
        val app = context.applicationContext
        val next = Organizer.edit(load(app, packageName), edit, System.currentTimeMillis())
        save(app, next)
        next
    }

    fun currentVersion(context: Context, packageName: String): String? =
        runCatching { context.packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()

    fun appLabel(context: Context, packageName: String): String =
        runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString() }.getOrNull() ?: packageName

    /** The glossary block for an agent in this app, or null when the app has no confirmed sets yet. */
    fun glossary(context: Context, packageName: String): String? = runCatching {
        Organizer.glossary(dictionary(context, packageName), appLabel(context, packageName)).takeIf { it.isNotBlank() }
    }.getOrNull()

    // ---- storage ----

    private fun file(context: Context, packageName: String): File = File(File(context.filesDir, "manual/dictionaries"), "$packageName.json")

    private fun load(context: Context, packageName: String): AppDictionary = cache.getOrPut(packageName) {
        val file = file(context, packageName)
        runCatching { if (file.isFile) DictionaryJson.read(JSONObject(file.readText())).copy(packageName = packageName) else null }.getOrNull()
            ?: AppDictionary(packageName)
    }

    private fun save(context: Context, dictionary: AppDictionary) {
        cache[dictionary.packageName] = dictionary
        val file = file(context, dictionary.packageName)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(DictionaryJson.write(dictionary).toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun lexicon(context: Context, packageName: String): AppLexicon {
        val code = runCatching { context.packageManager.getPackageInfo(packageName, 0).longVersionCode }.getOrDefault(-1L)
        lexicons[packageName]?.takeIf { it.first == code }?.let { return it.second }
        val built = AndroidLexicon.build(context, packageName)
        if (lexicons.size > 8) lexicons.clear()
        lexicons[packageName] = code to built
        return built
    }

    /** Raw accessibility nodes of a capture, as the reader needs them. */
    internal fun nodes(captured: com.cyclone.mobile.gateway.GatewayObservation): List<UiNode> =
        captured.elements.values.filter { it.source == "raw_accessibility" }.mapNotNull { element ->
            val e = element.evidence
            val bounds = e.optJSONObject("bounds") ?: return@mapNotNull null
            UiNode(
                id = e.optString("id").ifBlank { return@mapNotNull null },
                parentId = e.optString("parentId").takeIf { it.isNotBlank() && it != "null" },
                text = e.optString("text"),
                description = e.optString("contentDescription"),
                resourceId = e.optString("resourceId"),
                className = e.optString("class"),
                role = e.optString("role"),
                left = bounds.optInt("left"), top = bounds.optInt("top"), right = bounds.optInt("right"), bottom = bounds.optInt("bottom"),
                clickable = e.optBoolean("clickable"),
                selected = e.optBoolean("selected"),
                scrollable = e.optBoolean("scrollable"),
                editable = e.optBoolean("editable"),
                password = e.optBoolean("password"),
                visible = e.optBoolean("visibleToUser", true),
            )
        }

    // ---- the judge and JEV ----

    /** The pass's model answering the organizer's one question. Null without a key or model: candidates then wait. */
    private fun modelJudge(context: Context, appLabel: String, modelId: String): OrganizerJudge? {
        val key = OpenRouterSecretStore.read(context).takeIf { it.isNotBlank() } ?: return null
        val id = modelId.takeIf { it.isNotBlank() } ?: return null
        val preset = OpenRouterCatalogStore.preset(context, id)
        return object : OrganizerJudge {
            override val label: String = preset.label
            override fun decide(questions: List<OrganizerQuestion>): List<OrganizerDecision> {
                val model = OpenRouterMindModel(key, id, preset.label, preset.vision, null, "manual-organizer", ProviderCancellation(), { false })
                val messages = JSONArray()
                    .put(JSONObject().put("role", "system").put("content", OrganizerPrompt.SYSTEM))
                    .put(JSONObject().put("role", "user").put("content", OrganizerPrompt.user(appLabel, questions)))
                val reply = model.complete(MindModelRequest(messages, emptyList(), nativeTools = false, budgetMs = 60_000))
                return OrganizerPrompt.parse(reply.text, questions)
            }
        }
    }

    /** Passes the model's answers through and lets JEV answer the same questions next to it, for the tally only. */
    private class WatchedJudge(private val inner: OrganizerJudge, private val watch: (List<OrganizerQuestion>, List<OrganizerDecision>) -> Unit) : OrganizerJudge {
        override val label: String get() = inner.label
        override fun decide(questions: List<OrganizerQuestion>): List<OrganizerDecision> {
            val decisions = inner.decide(questions)
            runCatching { watch(questions, decisions) }
            return decisions
        }
    }

    private fun jevWatching(context: Context): Boolean =
        context.getSharedPreferences("cyclone_drive", Context.MODE_PRIVATE).getBoolean("jev_watch", true)

    /** JEV answers each question as a typed choice; only agreement and time are kept. Its answer is never applied. */
    private fun watchJev(context: Context, packageName: String, appLabel: String, questions: List<OrganizerQuestion>, decisions: List<OrganizerDecision>) {
        if (!jevWatching(context)) return
        val key = OpenRouterSecretStore.read(context).takeIf { it.isNotBlank() } ?: return
        val voice = com.cyclone.mobile.voice.OpenRouterVoice(key)
        for (question in questions) {
            val started = System.currentTimeMillis()
            val outcome = runCatching { OrganizerPrompt.jevParse(voice.decide(OrganizerPrompt.jevRequest(appLabel, question)), question) }
            val ms = System.currentTimeMillis() - started
            synchronized(lock) {
                val dict = load(context, packageName)
                val next = outcome.fold(
                    onSuccess = { pick ->
                        if (pick == null) dict.copy(jev = dict.jev.copy(lastError = "no usable answer"))
                        else {
                            val model = OrganizerPrompt.asJevChoice(decisions.firstOrNull { it.key == question.key })
                            dict.copy(jev = dict.jev.add(pick.first == model, pick.second, ms))
                        }
                    },
                    onFailure = { error -> dict.copy(jev = dict.jev.copy(lastError = (error.message ?: error.javaClass.simpleName).take(120))) },
                )
                save(context, next)
            }
        }
    }

    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
}
