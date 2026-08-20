package com.cyclone.mobile.platform.capability

/** Broad product area used only for discovery and diagnostics. */
enum class CapabilityFamily {
    PHONE,
    PAGE,
    BRAIN,
    AUTOMATION,
    VISION,
    GATEWAY,
}

/**
 * Descriptive policy metadata. The Policy Governor remains the authority that decides whether an
 * action is allowed; a value here can never grant authority.
 */
@JvmInline
value class CapabilityPolicyCategory(val value: String) : Comparable<CapabilityPolicyCategory> {
    init {
        require(runCatching { CapabilityId(value) }.isSuccess) {
            "Policy category must be a lowercase namespaced identifier: $value"
        }
    }

    override fun compareTo(other: CapabilityPolicyCategory): Int = value.compareTo(other.value)
    override fun toString(): String = value
}

object CyclonePolicyCategories {
    val ROUTINE = CapabilityPolicyCategory("policy.routine")
    val PRIVACY_SENSITIVE = CapabilityPolicyCategory("policy.privacy-sensitive")
    val EXTERNAL_COMMUNICATION = CapabilityPolicyCategory("policy.external-communication")
}

data class KnownCapability(
    val id: CapabilityId,
    val family: CapabilityFamily,
    val summary: String,
    val policyCategory: CapabilityPolicyCategory,
)

/**
 * Canonical capability names for compiled-in adapters. These are metadata identifiers, not new
 * phone implementations, and registering one does not authorize or execute it.
 */
object CycloneCapabilityFamilies {
    val PHONE_OBSERVE = CapabilityId("phone.observe")
    val PHONE_SEARCH = CapabilityId("phone.search")
    val PHONE_CLICK = CapabilityId("phone.click")
    val PHONE_TYPE = CapabilityId("phone.type")
    val PHONE_SWIPE = CapabilityId("phone.swipe")
    val PHONE_BACK = CapabilityId("phone.back")
    val PHONE_OPEN_APP = CapabilityId("phone.open-app")

    val PAGE_OBSERVE = CapabilityId("page.observe")
    val PAGE_IDENTIFY = CapabilityId("page.identify")
    val PAGE_SEARCH = CapabilityId("page.search")

    val BRAIN_RECALL = CapabilityId("brain.recall")
    val BRAIN_STORE = CapabilityId("brain.store")

    val AUTOMATION_LIST = CapabilityId("automation.list")
    val AUTOMATION_RUN = CapabilityId("automation.run")

    val VISION_INSPECT = CapabilityId("vision.inspect")
    val GATEWAY_STATUS = CapabilityId("gateway.status")

    val known: List<KnownCapability> = listOf(
        known(PHONE_OBSERVE, CapabilityFamily.PHONE, "Observe the current phone state"),
        known(PHONE_SEARCH, CapabilityFamily.PHONE, "Search the current phone state semantically"),
        known(PHONE_CLICK, CapabilityFamily.PHONE, "Request a typed click through the canonical action path"),
        KnownCapability(
            PHONE_TYPE,
            CapabilityFamily.PHONE,
            "Request redacted text entry through the canonical action path",
            CyclonePolicyCategories.PRIVACY_SENSITIVE,
        ),
        known(PHONE_SWIPE, CapabilityFamily.PHONE, "Request a typed swipe through the canonical action path"),
        known(PHONE_BACK, CapabilityFamily.PHONE, "Request canonical Android back navigation"),
        known(PHONE_OPEN_APP, CapabilityFamily.PHONE, "Request opening an installed application"),
        known(PAGE_OBSERVE, CapabilityFamily.PAGE, "Read compact semantic page evidence"),
        known(PAGE_IDENTIFY, CapabilityFamily.PAGE, "Identify the current semantic page"),
        known(PAGE_SEARCH, CapabilityFamily.PAGE, "Search semantic controls on the current page"),
        known(BRAIN_RECALL, CapabilityFamily.BRAIN, "Recall scoped Cyclone knowledge"),
        KnownCapability(
            BRAIN_STORE,
            CapabilityFamily.BRAIN,
            "Propose durable Cyclone knowledge through the governed memory seam",
            CyclonePolicyCategories.PRIVACY_SENSITIVE,
        ),
        known(AUTOMATION_LIST, CapabilityFamily.AUTOMATION, "List available Cyclone routines"),
        known(AUTOMATION_RUN, CapabilityFamily.AUTOMATION, "Request a governed Cyclone routine run"),
        KnownCapability(
            VISION_INSPECT,
            CapabilityFamily.VISION,
            "Inspect visual evidence only after semantic evidence is insufficient",
            CyclonePolicyCategories.PRIVACY_SENSITIVE,
        ),
        known(GATEWAY_STATUS, CapabilityFamily.GATEWAY, "Read authenticated local gateway health"),
    ).sortedBy { it.id }

    private val byId = known.associateBy { it.id }

    fun describe(id: CapabilityId): KnownCapability? = byId[id]

    private fun known(id: CapabilityId, family: CapabilityFamily, summary: String) =
        KnownCapability(id, family, summary, CyclonePolicyCategories.ROUTINE)
}
