package com.cyclone.mobile.runtime.background

import com.cyclone.mobile.ui.overlay.TaskAttachment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class PendingWorkspaceRequest(val id: String, val goal: String, val attachment: TaskAttachment?)

/** In-memory requests awaiting user start, not an execution plane or a second runner. */
class WorkspaceRequestQueue(private val capacity: Int = 8) {
    private val mutable = MutableStateFlow<List<PendingWorkspaceRequest>>(emptyList())
    val state = mutable.asStateFlow()
    @Synchronized fun add(goal: String, attachment: () -> TaskAttachment? = { null }): PendingWorkspaceRequest {
        require(goal.isNotBlank() && goal.length <= 2000) { "Describe a task in 2,000 characters or fewer." }
        check(mutable.value.size < capacity) { "Up next is full. Start or remove a saved task first." }
        val request = PendingWorkspaceRequest(UUID.randomUUID().toString(), goal.trim(), attachment())
        mutable.value = mutable.value + request
        return request
    }
    @Synchronized fun find(id: String) = mutable.value.firstOrNull { it.id == id }
    @Synchronized fun remove(id: String) { mutable.value = mutable.value.filterNot { it.id == id } }
}
