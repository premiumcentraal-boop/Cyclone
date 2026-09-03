package com.cyclone.mobile.agent.tools

import com.cyclone.mobile.agent.contract.AgentToolDescriptor
import com.cyclone.mobile.agent.contract.jsonArray
import org.json.JSONObject

object CompoundAgentToolCatalog {
    val tools: List<AgentToolDescriptor> = listOf(
        descriptor("open_app", "Open an installed Android app by human label. Resolves ambiguity, executes canonically, captures fresh after-state, and verifies the resulting package.", false, true,
            objSchema(required = listOf("name"), properties = mapOf("name" to str("Human app label, for example Chrome"))), actionResult()),
        descriptor("understand_page", "Read one fresh, goal-aware compact page packet with semantic controls, Brain/App Graph hints, app matches, prior verified delta, and screenshot eligibility.", true, false,
            objSchema(properties = mapOf("goal" to str("Current task goal"))), readResult("understand_page")),
        descriptor("recall", "Combine advisory Brain memory, App Graph route hints, installed-app matches, verified skills, and relevant prior failures for a goal.", true, false,
            objSchema(required = listOf("goal"), properties = mapOf("goal" to str("Task goal to recall knowledge for"))), readResult("recall")),
        descriptor("search", "Search several semantic queries against one authoritative observation generation and merge/rank current-snapshot candidates.", true, false,
            objSchema(required = listOf("queries"), properties = mapOf("queries" to arr(str("Semantic query")), "goal" to str("Optional overall goal"))), readResult("search")),
        descriptor("inspect", "Inspect several current observation-scoped element IDs in one read. Stale IDs are rejected deterministically.", true, false,
            objSchema(required = listOf("elementIds"), properties = mapOf("elementIds" to arr(str("Current observation-scoped element ID")))), readResult("inspect")),
        descriptor("visual_context", "Escalate perception with a screenshot reference/hash plus the current compact semantic Page Card and goal-ranked visual annotations. Screenshot is evidence only.", true, false,
            objSchema(properties = mapOf("goal" to str("Current task goal"))), readResult("visual_context")),
        descriptor("click", "Click one current observation-scoped semantic element and return canonical execution, fresh after-state, verification, typed failure, and delta.", false, true,
            elementActionSchema(), actionResult()),
        descriptor("long_press", "Long-press one current observation-scoped semantic element and return verified after-state evidence.", false, true,
            elementActionSchema(), actionResult()),
        descriptor("type", "Type user-requested text into one current editable element. Plaintext is never returned; sensitive credential fields remain denied by canonical PhoneTypeEngine policy.", false, true,
            textActionSchema(), actionResult()),
        descriptor("replace_text", "Replace text in one current editable element through the same credential-safe canonical typing path.", false, true,
            textActionSchema(), actionResult()),
        descriptor("scroll", "Scroll the current page or current scrollable element in one direction and return verified after-state evidence.", false, true,
            objSchema(properties = mapOf("direction" to enumStr("forward", "backward"), "elementId" to str("Optional current scrollable element ID"), "goal" to str("Task goal"))), actionResult()),
        descriptor("back", "Perform Android Back serially through the canonical executor and return fresh verified after-state.", false, true,
            objSchema(properties = mapOf("goal" to str("Task goal"))), actionResult()),
        descriptor("home", "Go to Android Home serially through the canonical executor and return fresh verified after-state.", false, true,
            objSchema(properties = mapOf("goal" to str("Task goal"))), actionResult()),
        descriptor("run_skill", "Run one verified/reviewable Cyclone route skill as a bounded sequence of canonical actions. Every consequential transition is re-observed and verified; execution stops on stale, policy, or verification failure.", false, true,
            objSchema(required = listOf("skillId"), properties = mapOf("skillId" to str("Verified skill ID returned by recall"), "goal" to str("Optional task goal"))), readResult("run_skill")),
    )

    fun descriptorsJson() = jsonArray(tools.map(AgentToolDescriptor::toJson))

    private fun descriptor(name: String, description: String, readOnly: Boolean, mutation: Boolean, args: JSONObject, result: JSONObject) =
        AgentToolDescriptor(name, description, args, result, readOnly, mutation)

    private fun elementActionSchema() = objSchema(required = listOf("elementId"), properties = mapOf(
        "elementId" to str("Current observation-scoped element ID"),
        "goal" to str("Task goal"),
    ))

    private fun textActionSchema() = objSchema(required = listOf("elementId", "value"), properties = mapOf(
        "elementId" to str("Current editable element ID"),
        "value" to JSONObject().put("type", "string").put("writeOnly", true).put("maxLength", 4096),
        "goal" to str("Task goal"),
    ))

    private fun actionResult() = JSONObject()
        .put("type", "object")
        .put("description", "success is semantic verification, never transport/executor acceptance alone")
        .put("required", jsonArray(listOf("success", "execution", "verification", "failure", "delta")))

    private fun readResult(kind: String) = JSONObject()
        .put("type", "object")
        .put("description", "$kind bounded provider-neutral result; raw accessibility trees and screenshot Base64 are excluded")

    private fun objSchema(required: List<String> = emptyList(), properties: Map<String, JSONObject> = emptyMap()): JSONObject =
        JSONObject().put("type", "object")
            .put("additionalProperties", false)
            .put("properties", JSONObject().also { p -> properties.forEach { (k, v) -> p.put(k, v) } })
            .apply { if (required.isNotEmpty()) put("required", jsonArray(required)) }

    private fun str(description: String) = JSONObject().put("type", "string").put("description", description)
    private fun arr(items: JSONObject) = JSONObject().put("type", "array").put("items", items).put("minItems", 1).put("maxItems", 8)
    private fun enumStr(vararg values: String) = JSONObject().put("type", "string").put("enum", jsonArray(values.asList()))
}
