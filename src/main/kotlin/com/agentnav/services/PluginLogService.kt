package com.agentnav.services

import com.agentnav.core.*
import com.agentnav.claude.*
import com.agentnav.acp.*
import com.agentnav.ui.*
import com.agentnav.services.*
import com.agentnav.settings.*

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Ring buffer en mémoire des events significatifs du plugin (control_request/response,
 * state changes, parse fails, permission requests, etc.). Permet à l'user d'inspecter
 * sans avoir à fouiller `idea.log`. Limité à N entries pour éviter les fuites mémoire.
 */
@Service(Service.Level.PROJECT)
class PluginLogService {

    enum class Level { INFO, WARN, ERROR, DEBUG }

    data class Entry(
        val timestamp: Instant,
        val level: Level,
        val category: String,
        val message: String
    )

    private val maxEntries = 2000
    private val entries = CopyOnWriteArrayList<Entry>()
    private val listeners = CopyOnWriteArrayList<(Entry) -> Unit>()

    fun log(level: Level, category: String, message: String) {
        val e = Entry(Instant.now(), level, category, message)
        entries.add(e)
        if (entries.size > maxEntries) {
            entries.removeAt(0)
        }
        listeners.toList().forEach { it(e) }
    }

    fun info(category: String, message: String) = log(Level.INFO, category, message)
    fun warn(category: String, message: String) = log(Level.WARN, category, message)
    fun error(category: String, message: String) = log(Level.ERROR, category, message)
    fun debug(category: String, message: String) = log(Level.DEBUG, category, message)

    fun snapshot(): List<Entry> = entries.toList()

    fun clear() {
        entries.clear()
        listeners.toList().forEach { it(Entry(Instant.now(), Level.INFO, "log", "(cleared)")) }
    }

    fun addListener(l: (Entry) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Entry) -> Unit) { listeners.remove(l) }

    companion object {
        fun getInstance(project: Project): PluginLogService =
            project.getService(PluginLogService::class.java)
    }
}
