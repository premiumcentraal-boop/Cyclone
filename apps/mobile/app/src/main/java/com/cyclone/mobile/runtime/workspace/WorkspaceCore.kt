package com.cyclone.mobile.runtime.workspace

enum class WorkspaceState { IDLE, RUNNING, PAUSED, GATED }

data class CycloneWorkspace(
    val id: String,
    val label: String,
    val appPackage: String,
    val androidUserId: Int = 0,
    val displayId: Int = 0,
    val state: WorkspaceState = WorkspaceState.IDLE,
) {
    init {
        require(id.isNotBlank()) { "workspace id required" }
        require(label.isNotBlank()) { "workspace label required" }
        require(appPackage.isNotBlank()) { "workspace package required" }
        require(androidUserId >= 0) { "androidUserId must be non-negative" }
        require(displayId >= 0) { "displayId must be non-negative" }
    }
}

interface WorkspacePersistence {
    fun load(): List<CycloneWorkspace>
    fun save(workspaces: List<CycloneWorkspace>)
}

class WorkspaceRegistry(private val persistence: WorkspacePersistence? = null) {
    private val monitor = Any()
    private val entries = linkedMapOf<String, CycloneWorkspace>()

    init {
        persistence?.load().orEmpty().forEach { entries[it.id] = it }
    }

    fun upsert(workspace: CycloneWorkspace): CycloneWorkspace = synchronized(monitor) {
        entries[workspace.id] = workspace
        persistLocked()
        workspace
    }

    fun get(id: String): CycloneWorkspace? = synchronized(monitor) { entries[id] }

    fun require(id: String): CycloneWorkspace = get(id)
        ?: throw IllegalArgumentException("unknown workspace $id")

    fun list(): List<CycloneWorkspace> = synchronized(monitor) { entries.values.toList() }

    fun setState(id: String, state: WorkspaceState): CycloneWorkspace = synchronized(monitor) {
        val updated = requireLocked(id).copy(state = state)
        entries[id] = updated
        persistLocked()
        updated
    }

    fun remove(id: String): CycloneWorkspace? = synchronized(monitor) {
        val removed = entries.remove(id)
        persistLocked()
        removed
    }

    private fun requireLocked(id: String): CycloneWorkspace = entries[id]
        ?: throw IllegalArgumentException("unknown workspace $id")

    private fun persistLocked() {
        persistence?.save(entries.values.toList())
    }
}

/** One process-global authority token: at most one workspace can mutate at a time. */
class WorkspaceMutateLock {
    private val monitor = Any()
    private var holderId: String? = null

    fun holder(): String? = synchronized(monitor) { holderId }

    fun acquire(workspaceId: String): Boolean = synchronized(monitor) {
        require(workspaceId.isNotBlank())
        if (holderId == null || holderId == workspaceId) {
            holderId = workspaceId
            true
        } else false
    }

    fun release(workspaceId: String): Boolean = synchronized(monitor) {
        if (holderId != workspaceId) false else {
            holderId = null
            true
        }
    }

    fun forceRelease(): String? = synchronized(monitor) {
        holderId.also { holderId = null }
    }
}

data class WorkspaceVerification(
    val appPackage: String,
    val androidUserId: Int,
    val displayId: Int,
)

interface WorkspaceSwitchPlatform {
    fun launch(workspace: CycloneWorkspace): Boolean
    fun verify(workspace: CycloneWorkspace): WorkspaceVerification?
    fun rebindObservation(workspace: CycloneWorkspace): Boolean
}

data class WorkspaceSwitchResult(
    val ok: Boolean,
    val code: String,
    val message: String,
    val workspace: CycloneWorkspace? = null,
)

class WorkspaceSwitchEngine(
    private val registry: WorkspaceRegistry,
    private val mutateLock: WorkspaceMutateLock,
    private val platform: WorkspaceSwitchPlatform,
) {
    fun switch(id: String): WorkspaceSwitchResult {
        val target = registry.get(id)
            ?: return WorkspaceSwitchResult(false, "WORKSPACE_NOT_FOUND", "Unknown workspace $id")
        val gated = registry.list().firstOrNull { it.state == WorkspaceState.GATED }
        if (gated != null) {
            return WorkspaceSwitchResult(
                false,
                "GATE_ACTIVE",
                "Human confirmation is still required in ${gated.label}; workspace switching cannot bypass it.",
                target,
            )
        }
        if (target.displayId != 0) {
            return WorkspaceSwitchResult(false, "DISPLAY_MISMATCH", "Layer 2 switching remains fail-closed on display 0.", target)
        }

        val previousHolder = mutateLock.holder()
        val previous = previousHolder?.let(registry::get)
        // Release before changing foreground ownership. A failed switch intentionally leaves no holder.
        if (previousHolder != null) {
            mutateLock.release(previousHolder)
            if (previous != null) registry.setState(previous.id, WorkspaceState.PAUSED)
        }

        if (!platform.launch(target)) {
            return WorkspaceSwitchResult(false, "LAUNCH_FAILED", "Target package/user could not be launched safely.", target)
        }
        val verified = platform.verify(target)
            ?: return WorkspaceSwitchResult(false, "VERIFY_FAILED", "Target package did not become verifiable.", target)
        if (verified.appPackage != target.appPackage ||
            verified.androidUserId != target.androidUserId ||
            verified.displayId != target.displayId
        ) {
            return WorkspaceSwitchResult(false, "IDENTITY_MISMATCH", "Package/user/display verification did not match the workspace.", target)
        }
        if (!platform.rebindObservation(target)) {
            return WorkspaceSwitchResult(false, "OBSERVE_REBIND_FAILED", "Cyclone could not re-bind observation to the verified target.", target)
        }
        if (!mutateLock.acquire(target.id)) {
            return WorkspaceSwitchResult(false, "MUTATE_LOCK_BUSY", "Another workspace owns the mutate lock.", target)
        }
        return WorkspaceSwitchResult(true, "SWITCHED", "Workspace ${target.label} is active.", registry.setState(target.id, WorkspaceState.RUNNING))
    }
}
