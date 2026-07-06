package com.agentnav.acp

import com.agentnav.core.*
import com.agentnav.claude.*
import com.agentnav.acp.*
import com.agentnav.ui.*
import com.agentnav.services.*
import com.agentnav.settings.*

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Hub projet pour les agents ACP (OpenCode, customs). Possède LE process de chaque profile
 * (spawn lazy, 1 par profile.id) et route les messages JSON-RPC vers les AcpSessionBackend
 * enregistrés par sessionId. Aucun listener global : chaque backend reçoit uniquement les
 * events de SA session.
 *
 * Cycle de vie d'un process : openSession() → spawn si absent → initialize → session/new
 * par backend. closeSession() décrémente ; dernier backend fermé → kill du process.
 * Crash process → handleProcessDied() sur tous les backends rattachés + entry retirée
 * (respawn lazy au prochain openSession).
 */
@Service(Service.Level.PROJECT)
class AcpProcessHub(private val project: Project) {

    private val log = thisLogger()
    private val pluginLog get() = PluginLogService.getInstance(project)

    private val procs = ConcurrentHashMap<String, ProcEntry>()

    private inner class ProcEntry(val profile: AgentProfile) {
        var handler: OSProcessHandler? = null
        val lineBuffer = StringBuilder()
        val nextRequestId = AtomicLong(1)
        val pendingRequests = ConcurrentHashMap<Long, (JsonObject) -> Unit>()
        val sessions = ConcurrentHashMap<String, AcpSessionBackend>()
        val pendingBackends = CopyOnWriteArrayList<AcpSessionBackend>()
        @Volatile var initialized = false
    }

    // ─── API backends ────────────────────────────────────────────────────────

    /** Ouvre une session pour ce backend : spawn+initialize si besoin, puis session/new. */
    fun openSession(backend: AcpSessionBackend) {
        val entry = procs.computeIfAbsent(backend.profile.id) { ProcEntry(backend.profile) }
        synchronized(entry) {
            if (entry.handler == null) {
                if (!spawn(entry)) {
                    backend.handleProcessDied(-1, "spawn failed")
                    procs.remove(backend.profile.id)
                    return
                }
                entry.pendingBackends.add(backend)
                sendInitialize(entry)
            } else if (!entry.initialized) {
                entry.pendingBackends.add(backend)
            } else {
                sendNewSession(entry, backend)
            }
        }
    }

    /** Désenregistre le backend ; kill le process si c'était la dernière session. */
    fun closeSession(backend: AcpSessionBackend) {
        val entry = procs[backend.profile.id] ?: return
        backend.sessionId?.let { entry.sessions.remove(it) }
        entry.pendingBackends.remove(backend)
        if (entry.sessions.isEmpty() && entry.pendingBackends.isEmpty()) {
            log.info("Last ACP session closed for ${entry.profile.id}, killing process")
            try { entry.handler?.destroyProcess() } catch (_: Exception) {}
            procs.remove(backend.profile.id)
        }
    }

    /** Envoie une requête JSON-RPC ; onResponse reçoit la réponse complète (result ou error). */
    fun request(profileId: String, method: String, paramsJson: String, onResponse: (JsonObject) -> Unit) {
        val entry = procs[profileId] ?: run {
            log.warn("request($method) on unknown profile $profileId"); return
        }
        val id = entry.nextRequestId.getAndIncrement()
        entry.pendingRequests[id] = onResponse
        writeRaw(entry, """{"jsonrpc":"2.0","id":$id,"method":"$method","params":$paramsJson}""")
    }

    /** Envoie une notification JSON-RPC (sans id, sans réponse). */
    fun notify(profileId: String, method: String, paramsJson: String) {
        val entry = procs[profileId] ?: return
        writeRaw(entry, """{"jsonrpc":"2.0","method":"$method","params":$paramsJson}""")
    }

    /** Répond à une requête émise PAR l'agent (permission, fs). */
    fun respond(profileId: String, rpcId: Long, resultJson: String) {
        val entry = procs[profileId] ?: return
        writeRaw(entry, """{"jsonrpc":"2.0","id":$rpcId,"result":$resultJson}""")
    }

    // ─── Process ─────────────────────────────────────────────────────────────

    private fun spawn(entry: ProcEntry): Boolean {
        return try {
            val (resolvedCmd, resolvedArgs) = AgentBinaryResolver.resolveProfileCommand(entry.profile)
            val exeFile = File(resolvedCmd)
            val exePath = if (exeFile.isAbsolute && exeFile.canExecute()) {
                resolvedCmd
            } else if (resolvedCmd == "npx") {
                AgentBinaryResolver.resolveNpx() ?: run {
                    log.warn("Cannot find npx for ${entry.profile.displayName}"); return false
                }
            } else {
                AgentBinaryResolver.resolveCommandInPath(resolvedCmd) ?: run {
                    log.warn("Command '$resolvedCmd' not found"); return false
                }
            }

            val command = GeneralCommandLine().apply {
                this.exePath = exePath
                addParameters(resolvedArgs)
                project.basePath?.let { workDirectory = File(it) }
                withRedirectErrorStream(false)
                File(exePath).parentFile?.absolutePath?.let { binDir ->
                    environment["PATH"] = "$binDir:${System.getenv("PATH") ?: ""}"
                }
            }
            log.info("Starting ACP agent ${entry.profile.displayName}: ${command.commandLineString}")
            pluginLog.info("acp", "🚀 SPAWN ${entry.profile.id}: ${command.commandLineString}")

            val proc = OSProcessHandler(command)
            entry.handler = proc
            proc.addProcessListener(object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    if (proc !== entry.handler) return
                    when (outputType) {
                        ProcessOutputType.STDOUT -> handleStdout(entry, event.text)
                        ProcessOutputType.STDERR -> handleStderr(entry, event.text)
                    }
                }
                override fun processTerminated(event: ProcessEvent) {
                    if (proc !== entry.handler) return
                    log.warn("ACP agent ${entry.profile.id} terminated (exit ${event.exitCode})")
                    val backends = entry.sessions.values.toList() + entry.pendingBackends.toList()
                    procs.remove(entry.profile.id)
                    backends.forEach { it.handleProcessDied(event.exitCode, null) }
                }
            })
            proc.startNotify()
            true
        } catch (e: Exception) {
            log.error("Failed to spawn ACP agent ${entry.profile.id}", e)
            false
        }
    }

    private fun writeRaw(entry: ProcEntry, message: String) {
        try {
            entry.handler?.processInput?.let { input ->
                input.write("$message\n".toByteArray())
                input.flush()
            } ?: log.warn("writeRaw on dead process ${entry.profile.id}")
        } catch (e: Exception) {
            log.error("ACP write failed (${entry.profile.id})", e)
        }
    }

    // ─── Reader / dispatch ───────────────────────────────────────────────────

    private fun handleStdout(entry: ProcEntry, text: String) {
        entry.lineBuffer.append(text)
        while (entry.lineBuffer.contains('\n')) {
            val idx = entry.lineBuffer.indexOf('\n')
            val line = entry.lineBuffer.substring(0, idx).trim()
            entry.lineBuffer.delete(0, idx + 1)
            if (line.isEmpty()) continue
            if (!line.startsWith("{")) { log.info("(acp stdout) $line"); continue }
            try {
                handleMessage(entry, JsonParser.parseString(line).asJsonObject)
            } catch (e: Exception) {
                log.warn("ACP parse fail (len=${line.length}): ${e.message}", e)
                pluginLog.warn("acp", "parse-fail: ${line.take(160)}")
            }
        }
    }

    private fun handleStderr(entry: ProcEntry, text: String) {
        val trimmed = text.trimEnd('\n', '\r')
        if (trimmed.isEmpty()) return
        log.warn("ACP stderr (${entry.profile.id}): $trimmed")
        (entry.sessions.values.toList() + entry.pendingBackends.toList())
            .forEach { it.dispatchStderr(trimmed) }
    }

    private fun handleMessage(entry: ProcEntry, json: JsonObject) {
        val method = json.get("method")?.asString
        val idElem = json.get("id")?.takeIf { !it.isJsonNull }
        val id = idElem?.let { runCatching { it.asLong }.getOrNull() }
        val hasResult = json.has("result")
        val hasError = json.has("error")

        when {
            // Réponse à une de nos requêtes
            id != null && method == null && (hasResult || hasError) -> {
                entry.pendingRequests.remove(id)?.invoke(json)
                    ?: log.warn("Unhandled ACP response id=$id")
            }
            // Requête émise par l'agent
            id != null && method != null -> when (method) {
                "fs/write_text_file", "fs/writeTextFile" -> handleFileWrite(entry, id, json)
                "fs/read_text_file", "fs/readTextFile" -> handleFileRead(entry, id, json)
                "session/request_permission" -> {
                    val params = json.getAsJsonObject("params")
                    val sid = params?.get("sessionId")?.asString
                    val backend = sid?.let { entry.sessions[it] }
                    if (backend != null) backend.dispatchPermission(id, params)
                    else {
                        // Session inconnue : deny prudent (le legacy auto-acceptait — corrigé).
                        log.warn("permission request for unknown session $sid → cancelled")
                        respond(entry.profile.id, id, """{"outcome":{"outcome":"cancelled"}}""")
                    }
                }
                else -> {
                    log.warn("Unhandled ACP agent request: $method id=$id")
                    respond(entry.profile.id, id, "null")
                }
            }
            // Notification
            method != null -> when (method) {
                "session/update" -> {
                    val params = json.getAsJsonObject("params") ?: return
                    val sid = params.get("sessionId")?.asString
                    val backend = sid?.let { entry.sessions[it] }
                    backend?.dispatchUpdate(params) ?: log.info("session/update for unknown sid=$sid")
                }
                else -> log.info("ACP notification: $method")
            }
        }
    }

    // ─── fs/* (globaux, path-based — pas liés à une session) ─────────────────

    private fun handleFileWrite(entry: ProcEntry, rpcId: Long, json: JsonObject) {
        val params = json.getAsJsonObject("params")
        val filepath = params?.get("path")?.asString
        val newContent = params?.get("content")?.asString
        if (filepath == null || newContent == null) {
            respond(entry.profile.id, rpcId, "null"); return
        }
        // Contrat ACP : le client déclare fs.writeTextFile=true dans initialize, donc c'est à
        // NOUS d'écrire. (Le legacy ne le faisait pas et comptait sur l'agent — corrigé.)
        // Le suivi diff/PendingChanges passe par le VFS listener des AcpSessionBackend.
        try {
            File(filepath).writeText(newContent, Charsets.UTF_8)
            respond(entry.profile.id, rpcId, "null")
        } catch (e: Exception) {
            log.warn("fs/write_text_file failed for $filepath", e)
            writeRaw(entry, """{"jsonrpc":"2.0","id":$rpcId,"error":{"code":-32000,"message":"${e.message?.replace("\"", "'")}"}}""")
        }
    }

    private fun handleFileRead(entry: ProcEntry, rpcId: Long, json: JsonObject) {
        val filepath = json.getAsJsonObject("params")?.get("path")?.asString
        val content = filepath?.let { p ->
            runCatching { File(p).takeIf { it.exists() }?.readText() }.getOrNull()
        } ?: ""
        respond(entry.profile.id, rpcId, """{"content":${AcpJson.escape(content)}}""")
    }

    // ─── Handshake ───────────────────────────────────────────────────────────

    private fun sendInitialize(entry: ProcEntry) {
        val id = entry.nextRequestId.getAndIncrement()
        entry.pendingRequests[id] = { response ->
            if (response.has("error")) {
                log.warn("ACP initialize failed: ${response.get("error")}")
                val backends = entry.pendingBackends.toList()
                entry.pendingBackends.clear()
                backends.forEach { it.handleProcessDied(-1, "initialize failed: ${response.get("error")}") }
            } else {
                entry.initialized = true
                val waiting = entry.pendingBackends.toList()
                entry.pendingBackends.clear()
                waiting.forEach { sendNewSession(entry, it) }
            }
        }
        writeRaw(entry,
            """{"jsonrpc":"2.0","id":$id,"method":"initialize","params":""" +
            """{"protocolVersion":1,"clientCapabilities":{"fs":{"readTextFile":true,"writeTextFile":true}},""" +
            """"clientInfo":{"name":"AgentNav","version":"0.9.0"}}}""")
    }

    private fun sendNewSession(entry: ProcEntry, backend: AcpSessionBackend) {
        val id = entry.nextRequestId.getAndIncrement()
        entry.pendingRequests[id] = { response ->
            if (response.has("error")) {
                backend.handleProcessDied(-1, "session/new failed: ${response.get("error")}")
            } else {
                val result = response.getAsJsonObject("result")
                val sid = result?.get("sessionId")?.asString
                if (sid.isNullOrEmpty()) {
                    backend.handleProcessDied(-1, "session/new returned no sessionId")
                } else {
                    entry.sessions[sid] = backend
                    backend.handleSessionCreated(sid, result)
                }
            }
        }
        val cwd = (project.basePath ?: System.getProperty("user.home"))
            .replace("\\", "\\\\").replace("\"", "\\\"")
        writeRaw(entry, """{"jsonrpc":"2.0","id":$id,"method":"session/new","params":{"cwd":"$cwd","mcpServers":[]}}""")
    }

    companion object {
        fun getInstance(project: Project): AcpProcessHub =
            project.getService(AcpProcessHub::class.java)
    }
}

/** Helpers JSON partagés du module acp. */
object AcpJson {
    fun escape(text: String): String =
        "\"" + text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\""
}
