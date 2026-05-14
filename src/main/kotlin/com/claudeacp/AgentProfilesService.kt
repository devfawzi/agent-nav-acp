package com.claudeacp

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Gère la liste des profils d'agents ACP disponibles :
 *  - Builtin : Claude Code, OpenCode (read-only)
 *  - Custom : profils ajoutés manuellement par l'utilisateur (persistés)
 */
@Service(Service.Level.APP)
@State(name = "AgentProfilesSettings", storages = [Storage("AgentProfilesSettings.xml")])
class AgentProfilesService : PersistentStateComponent<AgentProfilesService.State> {

    /** Représentation sérialisable d'un profil custom (data class avec types simples). */
    data class CustomProfileData(
        var id: String = "",
        var displayName: String = "",
        var command: String = "",
        var argsCsv: String = ""
    )

    data class State(
        var customProfiles: MutableList<CustomProfileData> = mutableListOf(),
        var activeProfileId: String = AgentProfile.CLAUDE_CODE.id
    )

    private var state = State()
    override fun getState(): State = state
    override fun loadState(state: State) {
        this.state = state
    }

    fun getAllProfiles(): List<AgentProfile> {
        val customs = state.customProfiles.map { d ->
            AgentProfile(
                id = d.id,
                displayName = d.displayName,
                command = d.command,
                args = d.argsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                isBuiltin = false
            )
        }
        return AgentProfile.BUILTIN_PROFILES + customs
    }

    fun getActiveProfile(): AgentProfile {
        val id = state.activeProfileId
        return getAllProfiles().firstOrNull { it.id == id } ?: AgentProfile.CLAUDE_CODE
    }

    fun setActiveProfile(id: String) {
        state.activeProfileId = id
    }

    fun addCustom(name: String, command: String, args: List<String>): AgentProfile {
        val id = "custom-${System.currentTimeMillis()}"
        state.customProfiles.add(
            CustomProfileData(
                id = id,
                displayName = name,
                command = command,
                argsCsv = args.joinToString(",")
            )
        )
        return getAllProfiles().first { it.id == id }
    }

    fun updateCustom(id: String, name: String, command: String, args: List<String>) {
        state.customProfiles.firstOrNull { it.id == id }?.let {
            it.displayName = name
            it.command = command
            it.argsCsv = args.joinToString(",")
        }
    }

    fun removeCustom(id: String) {
        state.customProfiles.removeIf { it.id == id }
        if (state.activeProfileId == id) {
            state.activeProfileId = AgentProfile.CLAUDE_CODE.id
        }
    }

    companion object {
        fun getInstance(): AgentProfilesService = service()
    }
}
