package com.cyclone.mobile.runtime.workspace

import android.content.Context
import android.content.Intent
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.runtime.background.BackgroundSetup
import com.cyclone.mobile.runtime.session.SessionKernel
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private class AndroidWorkspacePersistence(context: Context) : WorkspacePersistence {
    private val prefs = context.applicationContext.getSharedPreferences("cyclone_layer2_workspaces_v1", Context.MODE_PRIVATE)

    override fun load(): List<CycloneWorkspace> = runCatching {
        val values = JSONArray(prefs.getString("registry", "[]") ?: "[]")
        buildList {
            for (index in 0 until values.length()) {
                val item = values.optJSONObject(index) ?: continue
                val state = runCatching { WorkspaceState.valueOf(item.optString("state", "IDLE")) }
                    .getOrDefault(WorkspaceState.IDLE)
                add(CycloneWorkspace(
                    id = item.getString("id"),
                    label = item.getString("label"),
                    appPackage = item.getString("appPackage"),
                    androidUserId = item.optInt("androidUserId", 0),
                    displayId = item.optInt("displayId", 0),
                    // A process restart cannot preserve the in-memory mutate lock. Never restore RUNNING as authoritative.
                    state = if (state == WorkspaceState.RUNNING) WorkspaceState.PAUSED else state,
                ))
            }
        }
    }.getOrDefault(emptyList())

    override fun save(workspaces: List<CycloneWorkspace>) {
        val out = JSONArray()
        workspaces.forEach { workspace ->
            out.put(JSONObject()
                .put("id", workspace.id)
                .put("label", workspace.label)
                .put("appPackage", workspace.appPackage)
                .put("androidUserId", workspace.androidUserId)
                .put("displayId", workspace.displayId)
                .put("state", workspace.state.name))
        }
        prefs.edit().putString("registry", out.toString()).apply()
    }
}

private class AndroidWorkspaceSwitchPlatform(private val context: Context) : WorkspaceSwitchPlatform {
    override fun launch(workspace: CycloneWorkspace): Boolean {
        // Layer 2 intentionally does not invent cross-user launch authority. User 0 / display 0 is the safe shipped path.
        if (workspace.androidUserId != 0 || workspace.displayId != 0) return false
        val intent = context.packageManager.getLaunchIntentForPackage(workspace.appPackage) ?: return false
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            true
        }.getOrDefault(false)
    }

    override fun verify(workspace: CycloneWorkspace): WorkspaceVerification? {
        if (workspace.androidUserId != 0 || workspace.displayId != 0) return null
        var observed: String? = null
        repeat(15) {
            observed = BackgroundSetup.foregroundPackage() ?: DeviceState.currentPackage
            if (observed == workspace.appPackage) {
                return WorkspaceVerification(observed!!, 0, 0)
            }
            runCatching { Thread.sleep(100L) }
        }
        return observed?.let { WorkspaceVerification(it, 0, 0) }
    }

    override fun rebindObservation(workspace: CycloneWorkspace): Boolean {
        if (workspace.androidUserId != 0 || workspace.displayId != 0) return false
        val service = CycloneAccessibilityService.instance ?: return false
        runCatching { service.observe(markFresh = true) }.getOrNull() ?: return false
        return (BackgroundSetup.foregroundPackage() ?: DeviceState.currentPackage) == workspace.appPackage
    }
}

object WorkspaceRuntime {
    private val monitor = Any()
    private val mutateLock = WorkspaceMutateLock()
    @Volatile private var registryRef: WorkspaceRegistry? = null
    @Volatile private var engineRef: WorkspaceSwitchEngine? = null

    fun initialize(context: Context) {
        if (registryRef != null) return
        synchronized(monitor) {
            if (registryRef != null) return
            val app = context.applicationContext
            val registry = WorkspaceRegistry(AndroidWorkspacePersistence(app))
            registryRef = registry
            engineRef = WorkspaceSwitchEngine(registry, mutateLock, AndroidWorkspaceSwitchPlatform(app))
        }
    }

    fun list(context: Context): List<CycloneWorkspace> {
        initialize(context)
        return requireNotNull(registryRef).list()
    }

    fun register(
        context: Context,
        label: String,
        appPackage: String,
        androidUserId: Int = 0,
        id: String = "ws-${UUID.randomUUID().toString().take(8)}",
    ): CycloneWorkspace {
        initialize(context)
        val workspace = CycloneWorkspace(
            id = id.trim(),
            label = label.trim(),
            appPackage = appPackage.trim(),
            androidUserId = androidUserId,
            displayId = 0,
        )
        return requireNotNull(registryRef).upsert(workspace)
    }

    fun switch(context: Context, id: String): WorkspaceSwitchResult {
        initialize(context)
        return SessionKernel.switch(requireNotNull(engineRef), id)
    }

    fun mutateLockHolder(context: Context): String? {
        initialize(context)
        return mutateLock.holder()
    }

    fun releaseMutateLock(context: Context): String? {
        initialize(context)
        return mutateLock.forceRelease()
    }

    fun pauseMutateLockHolder(context: Context): String? {
        initialize(context)
        val holder = mutateLock.forceRelease() ?: return null
        requireNotNull(registryRef).get(holder)?.let {
            if (it.state != WorkspaceState.GATED) requireNotNull(registryRef).setState(holder, WorkspaceState.PAUSED)
        }
        return holder
    }

    fun markGated(context: Context, id: String): CycloneWorkspace {
        initialize(context)
        mutateLock.release(id)
        return requireNotNull(registryRef).setState(id, WorkspaceState.GATED)
    }

    fun clearGate(context: Context, id: String): CycloneWorkspace {
        initialize(context)
        return requireNotNull(registryRef).setState(id, WorkspaceState.PAUSED)
    }
}
