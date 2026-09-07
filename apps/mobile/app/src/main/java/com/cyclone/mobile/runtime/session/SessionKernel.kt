package com.cyclone.mobile.runtime.session

import com.cyclone.mobile.runtime.workspace.WorkspaceSwitchEngine
import com.cyclone.mobile.runtime.workspace.WorkspaceSwitchResult
import org.json.JSONObject

/** Display-scoped session identity. Product hot-gate is 1; the store/API can hold N sessions. */
object SessionKernel {
    const val PRODUCT_HOT_BACKGROUND_LIMIT = 1
    const val API_SESSION_CAPACITY = 8

    fun bind(params: JSONObject): ExecutionContext = ExecutionRequestScope.bind(params)
    fun attach(params: JSONObject, context: ExecutionContext): JSONObject =
        ExecutionRequestScope.attach(params, context)
    fun snapshot(store: ExecutionSessionStore): List<ExecutionSession> = store.snapshot()
    fun list(store: ExecutionSessionStore): List<ExecutionSession> = store.snapshot()

    /**
     * Layer 2 time-sliced workspace switch. The switch engine owns the single mutate lock and
     * package/user/display verification. Session identity rules remain unchanged.
     */
    fun switch(engine: WorkspaceSwitchEngine, workspaceId: String): WorkspaceSwitchResult =
        engine.switch(workspaceId)
}
