package com.claudeacp

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Cache projet des memory paths annoncés par claude (system:init.memory_paths).
 * Alimenté par le câblage onMemoryPaths des panels ; lu par MemoryInspectorAction.
 */
@Service(Service.Level.PROJECT)
class MemoryPathsCache {
    @Volatile
    var paths: Map<String, String> = emptyMap()

    companion object {
        fun getInstance(project: Project): MemoryPathsCache =
            project.getService(MemoryPathsCache::class.java)
    }
}
