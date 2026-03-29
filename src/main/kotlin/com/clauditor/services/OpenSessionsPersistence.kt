package com.clauditor.services

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project

@State(
    name = "ClauditorOpenSessions",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)]
)
@Service(Service.Level.PROJECT)
class OpenSessionsPersistence : PersistentStateComponent<OpenSessionsPersistence.State> {

    class State {
        @JvmField
        var sessionIds: MutableList<String> = mutableListOf()
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    fun add(sessionId: String) {
        if (sessionId !in myState.sessionIds) {
            myState.sessionIds.add(sessionId)
        }
    }

    fun remove(sessionId: String) {
        myState.sessionIds.remove(sessionId)
    }

    fun getAll(): List<String> = myState.sessionIds.toList()

    companion object {
        fun getInstance(project: Project): OpenSessionsPersistence =
            project.getService(OpenSessionsPersistence::class.java)
    }
}
