package com.cyclone.mobile.mind.lab

import com.cyclone.mobile.decisions.DAnswer
import com.cyclone.mobile.decisions.DReply
import com.cyclone.mobile.mind.modes.Triage

/** Test helper (plan 58/63): the Triage answers a perfect decision model would give for a golden request. */
object GoldenSetOracle {
    /** The answers a perfect model would give, built from the labels. */
    fun answers(r: GoldenRequest): DReply {
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
}
