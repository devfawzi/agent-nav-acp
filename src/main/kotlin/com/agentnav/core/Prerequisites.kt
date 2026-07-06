package com.agentnav.core

import com.agentnav.core.*
import com.agentnav.claude.*
import com.agentnav.acp.*
import com.agentnav.ui.*
import com.agentnav.services.*
import com.agentnav.settings.*

import java.io.File

/**
 * Prérequis minimal : Claude Code CLI installé. npx reste utile pour OpenCode mais optionnel.
 */
data class Prerequisites(
    val claudeCliPath: String?,
    val claudeConfigDir: String?,
    val npxPath: String?
) {
    val allOk: Boolean get() = claudeCliPath != null
    val missing: List<String> = buildList {
        if (claudeCliPath == null) add("Claude Code CLI")
    }

    companion object {
        fun check(): Prerequisites = Prerequisites(
            claudeCliPath = AgentBinaryResolver.resolveClaudeCli(),
            claudeConfigDir = "${System.getProperty("user.home")}/.claude".takeIf { File(it).isDirectory },
            npxPath = AgentBinaryResolver.resolveNpx()
        )
    }
}
