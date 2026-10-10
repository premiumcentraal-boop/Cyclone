package com.cyclone.mobile.mind.lab

import com.cyclone.mobile.decisions.DAnswer
import com.cyclone.mobile.decisions.DReply
import com.cyclone.mobile.mind.modes.LocalFacts
import com.cyclone.mobile.mind.modes.Mode
import com.cyclone.mobile.mind.modes.ModeRouter
import com.cyclone.mobile.mind.modes.Triage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Plan 58 §11.3: the offline scorer. Free, in CI, on every push.
 *
 * 1. The golden set itself is well formed: 300+ requests, English and Dutch, other languages, every rung.
 * 2. **On-phone routing** (the grammar, the local answers and the rules, no model): whatever it decides must never put
 *    a request lower than its label. On a request with a risk flag that is the hard gate.
 * 3. **Triage's combining rules**, given the answers a perfect model would give (built from the labels): they must
 *    reproduce the labels. This checks the code that turns answers into a rung; the model's own answers are measured
 *    live by the watch and the Lab.
 */
class GoldenSetTest {
    private val world = GoldenSet.WORLD
    private val facts = LocalFacts(time = "14:05", date = "Friday 9 October", batteryPercent = 64, charging = false)

    private val requests: List<GoldenRequest> by lazy {
        val file = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .flatMap { dir -> sequenceOf(File(dir, "src/main/assets/${GoldenSet.ASSET}"), File(dir, "apps/mobile/app/src/main/assets/${GoldenSet.ASSET}")) }
            .first { it.isFile }
        GoldenSet.parse(file.readText())
    }

    @Test fun `the golden set is well formed`() {
        assertTrue("300+ requests", requests.size >= 300)
        val langs = requests.groupingBy { it.lang }.eachCount()
        assertTrue(langs.getValue("en") >= 100 && langs.getValue("nl") >= 50)
        assertTrue("10% other languages", (requests.size - langs.getValue("en") - langs.getValue("nl")) * 10 >= requests.size)
        for (rung in listOf("instant", "flash", "mind", "answer", "ignore")) assertTrue(rung, requests.any { it.rung == rung })
        assertTrue(requests.count { it.risks.isNotEmpty() } >= 40)
    }

    private fun rung(mode: Mode) = mode.name.lowercase()

    @Test fun `on-phone routing never puts a request below its label`() {
        val score = GoldenSet.score(requests) { r ->
            (ModeRouter.stage0(r.text, world, facts) ?: ModeRouter.forced(r.text, world))?.let { rung(it.mode) }
        }
        println("on-phone routing: ${score.toJson()}")
        assertEquals("risky requests routed too low: ${score.riskyUnder}", emptyList<String>(), score.riskyUnder)
        assertEquals("requests routed too low: ${score.under}", emptyList<String>(), score.under)
        // The grammar settles most single actions without any model.
        val actions = score.byTag.getValue("action")
        assertTrue("grammar coverage of single actions: $actions", (actions[GoldenVerdict.RIGHT] ?: 0) * 10 >= requests.count { it.tag == "action" } * 8)
    }

    /** The answers a perfect model would give, built from the labels. */
    private fun oracle(r: GoldenRequest): DReply {
        val difficulty = when (r.rung) {
            "instant" -> 0.1
            "flash" -> 1.1
            "mind" -> if (r.tag == "question") 1.5 else 2.9
            else -> 0.2
        }
        val answers = mutableMapOf<String, DAnswer>(
            "difficulty" to DAnswer.Score(difficulty, 0.9, emptyMap()),
            "capability" to DAnswer.Choice(r.capability ?: "none", 0.95, emptyMap()),
            "target" to DAnswer.Choice(r.target ?: "none", 0.95, emptyMap()),
            "is_for_cyclone" to DAnswer.Noul(if (r.rung == "ignore") 0.05 else 0.98),
            "answer_only" to DAnswer.Noul(if (r.tag == "question") 0.9 else 0.05),
            "later" to DAnswer.Noul(if (r.tag == "later") 0.95 else 0.02),
            "writes_text" to DAnswer.Noul(if ("sends_or_posts" in r.risks || r.text.contains("note") || r.text.contains("notitie")) 0.9 else 0.05),
            "multi_app" to DAnswer.Noul(if (r.tag == "judgement") 0.6 else 0.05),
        )
        Triage.RISKS.forEach { answers[it] = DAnswer.Noul(if (it in r.risks) 0.92 else 0.03) }
        return DReply(answers)
    }

    @Test fun `triage's rules reproduce the labels from perfect answers`() {
        // Calls, clocks and local answers are settled on the phone before Triage is asked; open-app paraphrases need
        // the app to exist. Everything else goes through Triage in the router.
        val scored = requests.filter { it.tag !in GoldenSet.ON_PHONE_TAGS && it.rung != "answer" }
        val score = GoldenSet.score(scored) { r -> rung(Triage.route(Triage.read(oracle(r)), r.text, world, 0.85).mode) }
        println("triage with perfect answers: ${score.toJson()}")
        assertEquals(emptyList<String>(), score.riskyUnder)
        assertEquals(emptyList<String>(), score.under)
        assertEquals("every request on its labelled rung or an accepted one", scored.size, score.count(GoldenVerdict.RIGHT))
    }

    @Test fun `verdicts point the right way`() {
        val r = GoldenRequest("x", "pay the bill", "en", "mind", listOf("mind"), risks = listOf("money"))
        assertEquals(GoldenVerdict.UNDER, GoldenSet.verdict(r, "flash"))
        assertEquals(GoldenVerdict.UNDER, GoldenSet.verdict(r, "ignore"))
        assertEquals(GoldenVerdict.UNDECIDED, GoldenSet.verdict(r, null))
        val chatter = GoldenRequest("y", "ok", "en", "ignore", listOf("ignore"))
        assertEquals(GoldenVerdict.OVER, GoldenSet.verdict(chatter, "mind"))
        val camera = GoldenRequest("z", "open camera", "en", "instant", listOf("instant"))
        assertEquals(GoldenVerdict.OVER, GoldenSet.verdict(camera, "flash"))
        assertEquals(listOf("x"), GoldenSet.score(listOf(r, camera)) { if (it.id == "x") "instant" else "instant" }.riskyUnder)
    }
}
