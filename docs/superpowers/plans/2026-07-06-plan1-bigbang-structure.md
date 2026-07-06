# Plan 1 — Snapshot + Big Bang Structure (rework AgentNav)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sauvegarder la sandbox divergée, extraire la gestion ACP du legacy `ClaudeACPService` (2532 l.) dans un module `acp/` isolé, supprimer le legacy, puis renommer le plugin en AgentNav (`com.agentnav`) avec la structure de packages définitive.

**Architecture:** Modèle « 1 panel = 1 ChatSession = 1 AgentBackend » généralisé aux deux transports. `AcpProcessHub` (service projet) possède les process ACP (1 par profile) et route les messages JSON-RPC vers des `AcpSessionBackend` isolés par sessionId — zéro listener global. Le rename package se fait APRÈS l'extraction pour que les numéros de ligne du legacy restent valides pendant l'extraction.

**Tech Stack:** Kotlin 2.3.21, IntelliJ Platform SDK 2026.1.1 (Gradle plugin 2.15.0), Gson, JDK 21.

**Spec:** `docs/superpowers/specs/2026-07-06-agentnav-rework-design.md`

**Répertoire d'exécution : `/home/fawzi/Dev/agent-nav-acp` (le repo git). La sandbox `/home/fawzi/Dev/HQDP/claude-acp-plugin` n'est plus modifiée après la Task 1.**

**Note TDD :** ce plan est un refactor structurel sans harness de test existant (le harness arrive au Plan « protocole », conformément à l'approche big bang validée par l'user dans le spec). Les gates de chaque tâche sont : `./gradlew compileKotlin --no-daemon` vert + smoke `runIde` final (Task 10). Ne JAMAIS committer si la compilation échoue.

---

### Task 1: Snapshot sandbox → repo git

La sandbox contient la refonte `AgentBackend` du 2026-05-18 (`core/`, `backend/`) absente du repo git. On la sauvegarde AVANT tout.

**Files:**
- Sync: `src/`, `skills/`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `CONTEXT.md`, `IMPROVEMENTS.md`, `FEEDBACKS.md`, `README.md`

- [ ] **Step 1: Vérifier l'état de départ du repo**

Run: `cd /home/fawzi/Dev/agent-nav-acp && git status --short && git branch --show-current`
Expected: sortie vide (clean) + `main`. Si le repo n'est pas clean, s'arrêter et demander à l'user.

- [ ] **Step 2: Copier la sandbox (sans les artefacts de test)**

```bash
cd /home/fawzi/Dev/agent-nav-acp
rsync -a --delete /home/fawzi/Dev/HQDP/claude-acp-plugin/src/ src/
rsync -a --delete /home/fawzi/Dev/HQDP/claude-acp-plugin/skills/ skills/
cp /home/fawzi/Dev/HQDP/claude-acp-plugin/build.gradle.kts \
   /home/fawzi/Dev/HQDP/claude-acp-plugin/settings.gradle.kts \
   /home/fawzi/Dev/HQDP/claude-acp-plugin/gradle.properties \
   /home/fawzi/Dev/HQDP/claude-acp-plugin/CONTEXT.md \
   /home/fawzi/Dev/HQDP/claude-acp-plugin/IMPROVEMENTS.md \
   /home/fawzi/Dev/HQDP/claude-acp-plugin/FEEDBACKS.md \
   /home/fawzi/Dev/HQDP/claude-acp-plugin/README.md .
```

Ne PAS copier : `hello.txt`, `fichier1.txt`, `fichier2.txt`, `fawzi.txt`, `zzz`, `data.json`, `config.json`, `rapport-dcs-*.md`, `test-repo/`, `.gradle/`, `.idea/`, `.intellijPlatform/`, `build/` (artefacts de test de la sandbox).

- [ ] **Step 3: Vérifier que ça compile dans le repo**

Run: `cd /home/fawzi/Dev/agent-nav-acp && ./gradlew compileKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit snapshot + créer les branches**

```bash
cd /home/fawzi/Dev/agent-nav-acp
git checkout -b pre-rework-snapshot
git add -A
git commit -m "snapshot: état sandbox 2026-07-06 (refonte AgentBackend du 18/05 : core/, backend/, ClaudeCliBackend isolé)"
git checkout -b rework/agentnav
```

Run: `git log --oneline -2 && git branch --show-current`
Expected: le commit snapshot en tête + branche `rework/agentnav`.

---

### Task 2: Extraire `Prerequisites` hors du legacy

**Files:**
- Create: `src/main/kotlin/com/claudeacp/Prerequisites.kt`
- Modify: `src/main/kotlin/com/claudeacp/ClaudeACPService.kt` (lignes 360–380 : supprimer la data class + checkPrerequisites)
- Modify: `src/main/kotlin/com/claudeacp/OnboardingPanel.kt:87` (signature)
- Modify: `src/main/kotlin/com/claudeacp/ClaudeACPToolWindowPanel.kt:209`

- [ ] **Step 1: Créer le nouveau fichier**

```kotlin
package com.claudeacp

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
```

- [ ] **Step 2: Migrer les 2 consommateurs**

Dans `OnboardingPanel.kt:87` : remplacer `fun update(prerequisites: ClaudeACPService.Prerequisites)` par `fun update(prerequisites: Prerequisites)`.

Dans `ClaudeACPToolWindowPanel.kt:209` : remplacer `val prereqs = acpService.checkPrerequisites()` par `val prereqs = Prerequisites.check()`.

Dans `ClaudeACPService.kt` : supprimer les lignes 360–380 (le commentaire KDoc, la `data class Prerequisites` nested et `fun checkPrerequisites()`).

- [ ] **Step 3: Compiler**

Run: `./gradlew compileKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "refactor: extract Prerequisites from legacy ClaudeACPService"
```

---

### Task 3: Créer `AcpProcessHub`

Service projet qui possède les process ACP et route les messages. Extraction fidèle de la plomberie JSON-RPC du legacy (lignes 274–358 spawn, 382–444 reader/dispatch, 446–513 initialize/session-new, 870–880 writer), transformée : plus de listeners globaux ni d'état de session global — le routing se fait par sessionId vers les `AcpSessionBackend` enregistrés.

**Files:**
- Create: `src/main/kotlin/com/claudeacp/acp/AcpProcessHub.kt`

- [ ] **Step 1: Créer le fichier avec le code complet**

```kotlin
package com.claudeacp.acp

import com.claudeacp.AgentBinaryResolver
import com.claudeacp.AgentProfile
import com.claudeacp.PluginLogService
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
```

- [ ] **Step 2: Compiler** (le fichier référence `AcpSessionBackend` qui n'existe pas encore → créer d'abord un stub minimal OU faire Task 3 et Task 4 dans le même cycle de compilation. **Choisir : implémenter Task 4 avant de compiler.** Passer à Task 4 Step 1 directement, puis compiler les deux ensemble.)

---

### Task 4: Créer `AcpSessionBackend` (remplace le bridge `OpenCodeAcpBackend`)

Implémente `AgentBackend` pour UNE session ACP. Reçoit les events routés par le hub (aucun filtre par sid). Reprend la logique métier du legacy : `handleSessionUpdate` (l. 659–691), `handleToolCall` (l. 693–832), `parseSessionCapabilities` (l. 908–954), `parseSelectOptions` (l. 956–966), setters (l. 975–1005, 1160–1176), prompt (l. 515–577) — adaptée à l'état par-session. Les permissions affichent une card (le legacy auto-acceptait, l. 605–622 — corrigé).

**Files:**
- Create: `src/main/kotlin/com/claudeacp/acp/AcpSessionBackend.kt`
- Modify: `src/main/kotlin/com/claudeacp/core/ChatSession.kt:31-35` (instancier AcpSessionBackend)
- Delete: `src/main/kotlin/com/claudeacp/backend/OpenCodeAcpBackend.kt`

- [ ] **Step 1: Créer le fichier avec le code complet**

```kotlin
package com.claudeacp.acp

import com.claudeacp.AgentProfile
import com.claudeacp.PendingChangesService
import com.claudeacp.PluginLogService
import com.claudeacp.PromptAttachment
import com.claudeacp.PromptHistoryService
import com.claudeacp.DiffViewerManager
import com.claudeacp.core.AgentBackend
import com.claudeacp.core.AgentState
import com.claudeacp.core.ConfigOption
import com.claudeacp.core.PermissionRequest
import com.claudeacp.core.SelectOption
import com.claudeacp.core.SessionConfig
import com.claudeacp.core.ToolCallInfo
import com.claudeacp.core.UsageStats
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Backend ACP isolé : 1 instance = 1 session sur le process partagé du hub.
 * Le hub route directement les session/update et session/request_permission de NOTRE
 * sessionId — aucun listener global, aucun filtre.
 */
class AcpSessionBackend(
    private val project: Project,
    val profile: AgentProfile
) : AgentBackend {

    private val log = thisLogger()
    private val pluginLog get() = PluginLogService.getInstance(project)
    private val hub get() = AcpProcessHub.getInstance(project)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var _sessionId: String? = null
    override val sessionId: String? get() = _sessionId

    @Volatile private var _state: AgentState = AgentState.STOPPED
    override val state: AgentState get() = _state

    @Volatile private var _config: SessionConfig = SessionConfig()
    override val config: SessionConfig get() = _config

    @Volatile private var _usage: UsageStats = UsageStats()
    override val usage: UsageStats get() = _usage

    override var onStateChange: ((AgentState) -> Unit)? = null
    override var onSessionReady: ((String) -> Unit)? = null
    override var onTextChunk: ((String) -> Unit)? = null
    override var onThoughtChunk: ((String) -> Unit)? = null
    override var onToolCall: ((ToolCallInfo) -> Unit)? = null
    override var onPermission: ((PermissionRequest) -> Unit)? = null
    override var onExecuting: ((Boolean) -> Unit)? = null
    override var onConfigChange: ((SessionConfig) -> Unit)? = null
    override var onUsage: ((UsageStats) -> Unit)? = null
    override var onInfo: ((String) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null
    override var onStderr: ((String) -> Unit)? = null
    override var onToolResultError: ((String) -> Unit)? = null
    override var onMemoryPaths: ((Map<String, String>) -> Unit)? = null

    @Volatile private var executing = false
    private val toolCallPreCapturedBefore = ConcurrentHashMap<String, String>()
    private val pathsByToolCallId = ConcurrentHashMap<String, MutableSet<String>>()

    // ─── Cycle de vie ────────────────────────────────────────────────────────

    override fun start() {
        if (_state != AgentState.STOPPED && _state != AgentState.ERROR) return
        setState(AgentState.STARTING)
        setState(AgentState.INITIALIZING)
        hub.openSession(this)
    }

    override fun stop() {
        hub.closeSession(this)
        scope.cancel()
        setState(AgentState.STOPPED)
    }

    // ─── Callbacks internes appelés par le hub ───────────────────────────────

    internal fun handleSessionCreated(sid: String, result: JsonObject) {
        _sessionId = sid
        parseSessionCapabilities(result)
        setState(AgentState.READY)
        pluginLog.info("acp", "🟢 ACP session ready sid=$sid profile=${profile.id}")
        onSessionReady?.invoke(sid)
    }

    internal fun handleProcessDied(exitCode: Int, message: String?) {
        setExecuting(false)
        setState(AgentState.ERROR)
        onError?.invoke(message ?: "${profile.displayName} process died (exit $exitCode)")
    }

    internal fun dispatchStderr(text: String) {
        onStderr?.invoke(text)
    }

    internal fun dispatchUpdate(params: JsonObject) {
        val update = params.getAsJsonObject("update") ?: return
        val type = update.get("sessionUpdate")?.asString ?: return
        val text = extractTextFromUpdate(update)
        when (type) {
            "agent_message_chunk", "agentMessageChunk" ->
                if (!text.isNullOrEmpty()) onTextChunk?.invoke(text)
            "agent_thought_chunk", "agentThoughtChunk" ->
                if (!text.isNullOrEmpty()) onThoughtChunk?.invoke(text)
            "tool_call", "tool_call_update", "toolCall", "toolCallUpdate" ->
                handleToolCall(update)
            "usage_update", "usageUpdate", "available_commands_update" -> {}
            "current_mode_update", "currentModeUpdate" -> {
                val modeId = update.get("currentModeId")?.asString ?: update.get("modeId")?.asString
                if (modeId != null) updateConfig { it.copy(currentModeId = modeId) }
            }
            else -> if (!text.isNullOrEmpty()) onTextChunk?.invoke("[$type] $text")
        }
    }

    /**
     * Permission ACP → card Allow/Deny (le legacy auto-acceptait — corrigé).
     * Réponse : outcome selected(optionId) ou cancelled. Timeout 120s → deny.
     */
    internal fun dispatchPermission(rpcId: Long, params: JsonObject) {
        val options = params.getAsJsonArray("options")
        fun optionId(vararg kinds: String): String? = options?.firstOrNull {
            val obj = it.asJsonObject
            val kind = (obj.get("kind")?.asString ?: "").lowercase()
            val name = (obj.get("name")?.asString ?: "").lowercase()
            kinds.any { k -> k in kind || k in name }
        }?.asJsonObject?.get("optionId")?.asString

        val allowId = optionId("allow_once", "allow") ?: options?.firstOrNull()
            ?.asJsonObject?.get("optionId")?.asString ?: "allow"
        val allowAlwaysId = optionId("allow_always", "always")
        val rejectId = optionId("reject", "deny")

        val toolCall = params.getAsJsonObject("toolCall")
        val toolName = toolCall?.get("title")?.asString ?: toolCall?.get("kind")?.asString ?: "tool"
        val toolInput = toolCall?.getAsJsonObject("rawInput")?.toString()

        val answered = AtomicBoolean(false)
        fun respondOnce(json: String) {
            if (answered.compareAndSet(false, true)) hub.respond(profile.id, rpcId, json)
        }

        val request = PermissionRequest(
            requestId = rpcId.toString(),
            toolName = toolName,
            toolInput = toolInput,
            sessionId = _sessionId,
            respondAllow = {
                respondOnce("""{"outcome":{"outcome":"selected","optionId":"$allowId"}}""")
            },
            respondDeny = { _ ->
                respondOnce(
                    if (rejectId != null) """{"outcome":{"outcome":"selected","optionId":"$rejectId"}}"""
                    else """{"outcome":{"outcome":"cancelled"}}"""
                )
            },
            respondAllowAlways = allowAlwaysId?.let { id ->
                { respondOnce("""{"outcome":{"outcome":"selected","optionId":"$id"}}""") }
            }
        )
        // Timeout : ne pas bloquer l'agent indéfiniment si l'user ne répond pas.
        scope.launch {
            delay(120_000)
            if (!answered.get()) {
                log.warn("ACP permission timeout (120s) → deny")
                request.respondDeny("timeout")
            }
        }
        onPermission?.invoke(request) ?: run {
            // Pas de card câblée → deny prudent.
            log.warn("No onPermission handler wired → deny")
            request.respondDeny("no handler")
        }
    }

    // ─── Actions AgentBackend ────────────────────────────────────────────────

    override fun sendPrompt(text: String, attachments: List<PromptAttachment>) {
        val sid = _sessionId ?: run { onError?.invoke("No active ACP session"); return }
        project.getService(PromptHistoryService::class.java).startPrompt(text, sid)
        toolCallPreCapturedBefore.clear()
        setExecuting(true)

        val parts = mutableListOf("""{"type":"text","text":${AcpJson.escape(text)}}""")
        for (att in attachments) {
            when (att) {
                is PromptAttachment.FileLink -> {
                    val mime = att.mimeType?.let { ""","mimeType":${AcpJson.escape(it)}""" } ?: ""
                    parts.add("""{"type":"resource_link","uri":${AcpJson.escape("file://" + att.absolutePath)},"name":${AcpJson.escape(att.displayName)}$mime}""")
                }
                is PromptAttachment.Image ->
                    parts.add("""{"type":"image","data":${AcpJson.escape(att.base64Data)},"mimeType":${AcpJson.escape(att.mimeType)}}""")
                is PromptAttachment.CodeRef -> {
                    val block = "\n[Code reference from ${att.absolutePath}:${att.lineRange}]\n```${att.language.orEmpty()}\n${att.content}\n```\n"
                    parts.add("""{"type":"text","text":${AcpJson.escape(block)}}""")
                }
            }
        }
        val promptArray = parts.joinToString(",", prefix = "[", postfix = "]")
        hub.request(profile.id, "session/prompt", """{"sessionId":"$sid","prompt":$promptArray}""") { response ->
            if (response.has("error")) onError?.invoke("session/prompt failed: ${response.get("error")}")
            setExecuting(false)
        }
    }

    override fun cancel() {
        val sid = _sessionId ?: return
        hub.notify(profile.id, "session/cancel", """{"sessionId":"$sid"}""")
    }

    override fun replyToolResult(toolUseId: String, content: String) {
        // Pas d'équivalent ACP (ExitPlanMode/AskUserQuestion sont des concepts claude CLI).
        log.info("replyToolResult ignored on ACP transport")
    }

    override fun setMode(modeId: String) {
        val sid = _sessionId ?: return
        hub.request(profile.id, "session/set_mode",
            """{"sessionId":"$sid","modeId":${AcpJson.escape(modeId)}}""") { response ->
            if (response.has("error")) onError?.invoke("setMode failed: ${response.get("error")}")
            else updateConfig { it.copy(currentModeId = modeId) }
        }
    }

    override fun setModel(modelId: String) {
        val sid = _sessionId ?: return
        hub.request(profile.id, "session/set_model",
            """{"sessionId":"$sid","modelId":${AcpJson.escape(modelId)}}""") { response ->
            if (response.has("error")) onError?.invoke("setModel failed: ${response.get("error")}")
            else updateConfig { it.copy(currentModelId = modelId) }
        }
    }

    override fun setEffort(level: String) {
        val sid = _sessionId ?: return
        hub.request(profile.id, "session/set_config_option",
            """{"sessionId":"$sid","id":"thinking","value":${AcpJson.escape(level)}}""") { response ->
            if (response.has("error")) onError?.invoke("setEffort failed: ${response.get("error")}")
            else updateConfig { it.copy(currentConfigValues = it.currentConfigValues + ("thinking" to level)) }
        }
    }

    // ─── Tool calls + suivi fichiers (repris du legacy handleToolCall l.693–832) ──

    private fun handleToolCall(update: JsonObject) {
        val toolCallId = update.get("toolCallId")?.asString
        val status = update.get("status")?.asString
        val title = update.get("title")?.asString ?: update.get("kind")?.asString ?: "tool"
        val kind = update.get("kind")?.asString
            ?: update.getAsJsonObject("toolCall")?.get("kind")?.asString
        val rawInput = update.getAsJsonObject("rawInput")
        val pathFromInput = rawInput?.get("file_path")?.asString
            ?: rawInput?.get("path")?.asString ?: rawInput?.get("filePath")?.asString
        val command = rawInput?.get("command")?.asString

        val paths = mutableSetOf<String>()
        update.getAsJsonArray("content")?.forEach { item ->
            if (item.isJsonObject && item.asJsonObject.get("type")?.asString == "diff")
                item.asJsonObject.get("path")?.asString?.let { paths.add(it) }
        }
        update.getAsJsonArray("locations")?.forEach { item ->
            if (item.isJsonObject) item.asJsonObject.get("path")?.asString?.let { paths.add(it) }
        }

        val detail = when (title) {
            "Grep", "Glob" -> rawInput?.get("pattern")?.asString
            "WebFetch" -> rawInput?.get("url")?.asString
            "WebSearch" -> rawInput?.get("query")?.asString
            "Task" -> rawInput?.get("description")?.asString
                ?: rawInput?.get("subagent_type")?.asString
                ?: rawInput?.get("prompt")?.asString?.take(80)
            "TodoWrite" -> rawInput?.getAsJsonArray("todos")?.let { "${it.size()} item(s)" }
            "Skill" -> rawInput?.get("skill")?.asString
            "ToolSearch" -> rawInput?.get("query")?.asString
            "AskUserQuestion" -> "(question)"
            "ExitPlanMode" -> "(plan)"
            else -> if (title.startsWith("mcp__"))
                rawInput?.entrySet()?.joinToString(", ", limit = 3, truncated = "…") { "${it.key}=${it.value}" }
            else null
        }

        val info = ToolCallInfo(
            toolCallId = toolCallId, title = title, kind = kind, status = status,
            path = pathFromInput ?: paths.firstOrNull(), command = command,
            sessionId = _sessionId, detail = detail
        )
        val genericTitles = setOf("tool", "edit", "write", "read", "bash", "find", "grep")
        val isGenericOnly = title.lowercase() in genericTitles &&
            info.path == null && info.command == null && info.detail == null && status != "completed"
        if (!isGenericOnly) onToolCall?.invoke(info)

        if (toolCallId != null && paths.isNotEmpty())
            pathsByToolCallId.getOrPut(toolCallId) { mutableSetOf() }.addAll(paths)

        val allPaths = paths.toMutableSet()
        if (toolCallId != null) pathsByToolCallId[toolCallId]?.let { allPaths.addAll(it) }
        if (allPaths.isEmpty()) return

        for (path in allPaths) {
            if (!shouldTrackFile(path)) continue
            toolCallPreCapturedBefore.computeIfAbsent(path) { readFileContent(it) }
        }

        if (status == "completed") {
            scheduleRefreshAndFallback(allPaths.toSet())
            if (toolCallId != null) scope.launch { delay(5000); pathsByToolCallId.remove(toolCallId) }
        }
    }

    /** Retries VFS refresh + fallback addOrUpdate manuel (repris du legacy l.785–824). */
    private fun scheduleRefreshAndFallback(allPaths: Set<String>) {
        scope.launch {
            for ((i, d) in listOf(200L, 800L).withIndex()) {
                delay(d)
                ApplicationManager.getApplication().invokeLater {
                    for (path in allPaths) {
                        try {
                            LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
                                ?.refresh(false, false)
                        } catch (e: Exception) {
                            log.warn("refresh #${i + 1} failed for $path", e)
                        }
                    }
                }
            }
            delay(400)
            ApplicationManager.getApplication().invokeLater {
                val pending = project.getService(PendingChangesService::class.java)
                val history = project.getService(PromptHistoryService::class.java)
                for (path in allPaths) {
                    if (!shouldTrackFile(path)) continue
                    val before = toolCallPreCapturedBefore.remove(path) ?: continue
                    try {
                        val after = File(path).readText(Charsets.UTF_8)
                        if (before == after) continue
                        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path) ?: continue
                        history.captureFileBefore(path, before)
                        history.captureFileAfter(path, after)
                        pending.addOrUpdate(path, before, after, vf, _sessionId)
                        project.getService(DiffViewerManager::class.java).scheduleRefresh()
                    } catch (e: Exception) {
                        log.warn("Fallback addOrUpdate failed for $path", e)
                    }
                }
            }
        }
    }

    // ─── Helpers (repris du legacy) ──────────────────────────────────────────

    private fun shouldTrackFile(path: String): Boolean {
        val basePath = project.basePath ?: return false
        if (!path.startsWith(basePath)) return false
        val ignored = listOf(
            "/build/", "/.gradle/", "/.idea/", "/.git/", "/node_modules/",
            "/.intellijPlatform/", "/target/", "/out/", "/dist/", "/.next/",
            "/__pycache__/", "/venv/", "/.venv/"
        )
        return ignored.none { path.contains(it) }
    }

    private fun readFileContent(filepath: String): String = try {
        val file = LocalFileSystem.getInstance().findFileByPath(filepath)
        if (file != null && file.exists()) String(file.contentsToByteArray())
        else File(filepath).takeIf { it.exists() }?.readText() ?: ""
    } catch (e: Exception) {
        log.warn("Could not read file: $filepath", e); ""
    }

    private fun extractTextFromUpdate(update: JsonObject): String? {
        update.get("text")?.takeIf { it.isJsonPrimitive }?.asString?.let { return it }
        val content = update.get("content") ?: return null
        return when {
            content.isJsonPrimitive -> content.asString
            content.isJsonObject -> content.asJsonObject.get("text")?.asString
            content.isJsonArray -> buildString {
                content.asJsonArray.forEach { item ->
                    if (item.isJsonObject) item.asJsonObject.get("text")?.asString?.let { append(it) }
                    else if (item.isJsonPrimitive) append(item.asString)
                }
            }.ifEmpty { null }
            else -> null
        }
    }

    private fun parseSessionCapabilities(result: JsonObject) {
        var models: List<SelectOption> = emptyList()
        var modes: List<SelectOption> = emptyList()
        var configOptions: List<ConfigOption> = emptyList()
        var currentModel: String? = null
        var currentMode: String? = null
        val currentConfig = mutableMapOf<String, String>()

        result.getAsJsonObject("models")?.let { m ->
            models = parseSelectOptions(m.getAsJsonArray("availableModels"), "modelId")
            currentModel = m.get("currentModelId")?.asString
        }
        result.getAsJsonObject("modes")?.let { m ->
            modes = parseSelectOptions(m.getAsJsonArray("availableModes"), "id")
            currentMode = m.get("currentModeId")?.asString
        }
        result.getAsJsonArray("configOptions")?.let { arr ->
            configOptions = arr.mapNotNull { item ->
                if (!item.isJsonObject) return@mapNotNull null
                val obj = item.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                val name = obj.get("name")?.asString ?: obj.get("label")?.asString ?: id
                val type = obj.get("type")?.asString ?: "select"
                val payload = obj.getAsJsonObject("payload") ?: obj
                val opts = parseSelectOptions(payload.getAsJsonArray("options"), "id")
                val current = payload.get("currentValue")?.asString
                if (current != null) currentConfig[id] = current
                ConfigOption(id, name, type, opts, current)
            }
        }
        _config = SessionConfig(
            models = models, modes = modes, configOptions = configOptions,
            currentModelId = currentModel, currentModeId = currentMode,
            currentConfigValues = currentConfig
        )
        onConfigChange?.invoke(_config)
    }

    private fun parseSelectOptions(arr: com.google.gson.JsonArray?, idField: String): List<SelectOption> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { item ->
            if (!item.isJsonObject) return@mapNotNull null
            val obj = item.asJsonObject
            val id = obj.get(idField)?.asString ?: obj.get("id")?.asString ?: return@mapNotNull null
            val name = obj.get("name")?.asString ?: obj.get("label")?.asString ?: id
            SelectOption(id, name, obj.get("description")?.asString)
        }
    }

    private fun updateConfig(transform: (SessionConfig) -> SessionConfig) {
        _config = transform(_config)
        onConfigChange?.invoke(_config)
    }

    private fun setExecuting(value: Boolean) {
        if (executing == value) return
        executing = value
        onExecuting?.invoke(value)
        if (!value) project.getService(PromptHistoryService::class.java).endPrompt()
    }

    private fun setState(newState: AgentState) {
        val previous = _state
        _state = newState
        pluginLog.info("acp", "state $previous → $newState (sid=$_sessionId)")
        onStateChange?.invoke(newState)
    }
}
```

- [ ] **Step 2: Brancher ChatSession sur le nouveau backend**

Dans `core/ChatSession.kt`, remplacer :

```kotlin
    val backend: AgentBackend = when (profile.transport) {
        Transport.CLI_STREAM_JSON ->
            ClaudeCliBackend(project, profile, resumeSid = resumeSid, cwdOverride = cwdOverride)
        else -> OpenCodeAcpBackend(project, profile)
    }
```

par :

```kotlin
    val backend: AgentBackend = when (profile.transport) {
        Transport.CLI_STREAM_JSON ->
            ClaudeCliBackend(project, profile, resumeSid = resumeSid, cwdOverride = cwdOverride)
        else -> AcpSessionBackend(project, profile)
    }
```

et remplacer l'import `com.claudeacp.backend.OpenCodeAcpBackend` par `com.claudeacp.acp.AcpSessionBackend`. Mettre à jour le commentaire KDoc de la propriété (supprimer la mention « legacy ClaudeACPService (phase 3 reportée) »).

- [ ] **Step 3: Supprimer le bridge**

```bash
git rm src/main/kotlin/com/claudeacp/backend/OpenCodeAcpBackend.kt
```

- [ ] **Step 4: Compiler (Tasks 3+4 ensemble)**

Run: `./gradlew compileKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(acp): AcpProcessHub + AcpSessionBackend isolés — routing par sid, permission cards, fin du bridge global"
```

---

### Task 5: Migrer les derniers points de contact UI + `MemoryPathsCache`

**Files:**
- Create: `src/main/kotlin/com/claudeacp/MemoryPathsCache.kt`
- Modify: `src/main/kotlin/com/claudeacp/PromptInputPanel.kt` (l.62, 381, 397, 417, 1078)
- Modify: `src/main/kotlin/com/claudeacp/ClaudeACPToolWindowPanel.kt` (l.32 + méthode `wireBackend()`)
- Modify: `src/main/kotlin/com/claudeacp/ClaudeACPToolWindowFactory.kt` (l.124-125)

- [ ] **Step 1: Créer MemoryPathsCache**

Le legacy stockait `lastMemoryPaths` (alimenté par son bloc CLI mort — donc cassé depuis la refonte du 18/05). On le remplace par un cache projet alimenté par le câblage `onMemoryPaths` du panel.

```kotlin
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
```

- [ ] **Step 2: PromptInputPanel — supprimer les fallbacks legacy**

- l.62 : supprimer la déclaration `private val acpService = project.getService(ClaudeACPService::class.java)` (et l'import de `ClaudeACPService` s'il devient inutilisé).
- l.379–382 : remplacer `if (setModelCallback != null) setModelCallback.invoke(opt.id) else acpService.setModel(opt.id, targetSessionId = sid)` par `setModelCallback?.invoke(opt.id)`.
- l.395–398 : idem → `setModeCallback?.invoke(opt.id)`.
- l.415–418 : idem → `setEffortCallback?.invoke(opt.id)`.
- l.1074–1079 : remplacer le bloc `if (onAgentSwitchRequested != null) { onAgentSwitchRequested.invoke(profile) } else { acpService.switchAgent(profile) }` par `onAgentSwitchRequested?.invoke(profile)`.
- Si `getMySessionId()`/`val sid` deviennent inutilisés dans `buildModelSubmenu`/`buildModeSubmenu`/`buildEffortSubmenu`, les supprimer.

- [ ] **Step 3: ClaudeACPToolWindowPanel — supprimer acpService + câbler onMemoryPaths**

- l.32 : supprimer `private val acpService = project.getService(ClaudeACPService::class.java)`.
- Dans la méthode `wireBackend()` (chercher `private fun wireBackend`), ajouter à la fin du câblage des callbacks :

```kotlin
        backend.onMemoryPaths = { paths ->
            MemoryPathsCache.getInstance(project).paths = paths
        }
```

- [ ] **Step 4: Factory — lire le cache**

Dans `ClaudeACPToolWindowFactory.kt` l.124–125, remplacer :

```kotlin
            val acpService = project.getService(ClaudeACPService::class.java)
            val paths = acpService.lastMemoryPaths
```

par :

```kotlin
            val paths = MemoryPathsCache.getInstance(project).paths
```

- [ ] **Step 5: Compiler puis committer**

Run: `./gradlew compileKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`

```bash
git add -A && git commit -m "refactor: UI découplée du legacy — fallbacks morts supprimés, MemoryPathsCache alimenté par onMemoryPaths"
```

---

### Task 6: Supprimer `ClaudeACPService`

**Files:**
- Delete: `src/main/kotlin/com/claudeacp/ClaudeACPService.kt`
- Modify: `src/main/resources/META-INF/plugin.xml:73`
- Modify: `src/main/kotlin/com/claudeacp/AgentSettings.kt:161` (commentaire obsolète)

- [ ] **Step 1: Vérifier qu'il n'a plus de consommateurs**

Run: `grep -rn "ClaudeACPService" src/main/kotlin --include="*.kt" | grep -v "ClaudeACPService.kt:"`
Expected: uniquement des commentaires (ou rien). S'il reste des appels réels, corriger AVANT de supprimer (retour aux tasks précédentes).

- [ ] **Step 2: Supprimer**

```bash
git rm src/main/kotlin/com/claudeacp/ClaudeACPService.kt
```

Dans `plugin.xml`, supprimer la ligne `<projectService serviceImplementation="com.claudeacp.ClaudeACPService"/>`.
Dans `AgentSettings.kt` l.161, remplacer la mention `Appelé par ClaudeACPService au system:init` par `Appelé par ClaudeCliBackend au system:init`.
Nettoyer tout commentaire restant qui référence ClaudeACPService (grep du Step 1).

- [ ] **Step 3: Compiler puis committer**

Run: `./gradlew compileKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`

```bash
git add -A && git commit -m "refactor!: suppression du legacy ClaudeACPService (2532 l.) — CLI et ACP passent par les backends isolés"
```

---

### Task 7: Restructuration packages → `com.agentnav`

Rename + éclatement en 6 packages. Stratégie scriptable : (1) sed global du nom de package racine, (2) `git mv` par table de mapping + réécriture de la ligne `package`, (3) imports wildcard inter-packages insérés partout (raffinables plus tard via Optimize Imports de l'IDE), (4) compilation pour attraper les résidus.

**Mapping (fichier → package cible) :**

| Package cible | Fichiers |
|---|---|
| `com.agentnav.core` | AgentBackend.kt, AgentEvents.kt, ChatSession.kt, ChatFonts.kt (déjà dans core/) + AgentProfile.kt, PromptAttachment.kt, Prerequisites.kt |
| `com.agentnav.claude` | backend/ClaudeCliBackend.kt, ClaudeSessionsService.kt |
| `com.agentnav.acp` | acp/AcpProcessHub.kt, acp/AcpSessionBackend.kt (déjà dans acp/) |
| `com.agentnav.ui` | ChatPanel.kt, PromptInputPanel.kt, ClaudeACPToolWindowFactory.kt→AgentNavToolWindowFactory.kt, ClaudeACPToolWindowPanel.kt→AgentNavToolWindowPanel.kt, OnboardingPanel.kt, PendingChangesPanel.kt, ResumeSessionDialog.kt, MemoryInspectorDialog.kt, DiffCustomPaletteDialog.kt, PluginLogDialog.kt, FileMentionPopup.kt, SlashCommandPopup.kt, AttachmentChip.kt |
| `com.agentnav.services` | PendingChangesService.kt, DiffViewerManager.kt, DiffActions.kt, DiffPaletteService.kt, PromptHistoryService.kt, PluginLogService.kt, AgentProfilesService.kt, AgentBinaryResolver.kt, EditorSelectionGrabber.kt, EditorDiagnosticsGrabber.kt, AddSelectionToChatAction.kt, MemoryPathsCache.kt, AgentConnectionTester.kt |
| `com.agentnav.settings` | AgentSettings.kt, AgentSettingsConfigurable.kt |

- [ ] **Step 1: Sed global com.claudeacp → com.agentnav**

```bash
cd /home/fawzi/Dev/agent-nav-acp
find src -name "*.kt" -exec sed -i 's/com\.claudeacp/com.agentnav/g' {} +
sed -i 's/com\.claudeacp/com.agentnav/g' src/main/resources/META-INF/plugin.xml
```

- [ ] **Step 2: git mv + réécriture des packages (script complet)**

```bash
cd /home/fawzi/Dev/agent-nav-acp
BASE=src/main/kotlin/com/claudeacp
NEW=src/main/kotlin/com/agentnav
mkdir -p $NEW/{core,claude,acp,ui,services,settings}

# core
git mv $BASE/core/AgentBackend.kt $BASE/core/AgentEvents.kt $BASE/core/ChatSession.kt $BASE/core/ChatFonts.kt $NEW/core/
git mv $BASE/AgentProfile.kt $BASE/PromptAttachment.kt $BASE/Prerequisites.kt $NEW/core/
# claude
git mv $BASE/backend/ClaudeCliBackend.kt $BASE/ClaudeSessionsService.kt $NEW/claude/
# acp
git mv $BASE/acp/AcpProcessHub.kt $BASE/acp/AcpSessionBackend.kt $NEW/acp/
# ui (avec rename des 2 classes ToolWindow)
git mv $BASE/ClaudeACPToolWindowFactory.kt $NEW/ui/AgentNavToolWindowFactory.kt
git mv $BASE/ClaudeACPToolWindowPanel.kt $NEW/ui/AgentNavToolWindowPanel.kt
git mv $BASE/ChatPanel.kt $BASE/PromptInputPanel.kt $BASE/OnboardingPanel.kt \
       $BASE/PendingChangesPanel.kt $BASE/ResumeSessionDialog.kt $BASE/MemoryInspectorDialog.kt \
       $BASE/DiffCustomPaletteDialog.kt $BASE/PluginLogDialog.kt $BASE/FileMentionPopup.kt \
       $BASE/SlashCommandPopup.kt $BASE/AttachmentChip.kt $NEW/ui/
# services
git mv $BASE/PendingChangesService.kt $BASE/DiffViewerManager.kt $BASE/DiffActions.kt \
       $BASE/DiffPaletteService.kt $BASE/PromptHistoryService.kt $BASE/PluginLogService.kt \
       $BASE/AgentProfilesService.kt $BASE/AgentBinaryResolver.kt $BASE/EditorSelectionGrabber.kt \
       $BASE/EditorDiagnosticsGrabber.kt $BASE/AddSelectionToChatAction.kt $BASE/MemoryPathsCache.kt \
       $BASE/AgentConnectionTester.kt $NEW/services/
# settings
git mv $BASE/AgentSettings.kt $BASE/AgentSettingsConfigurable.kt $NEW/settings/
# le dossier claudeacp doit être vide
rmdir $BASE/core $BASE/backend $BASE/acp $BASE 2>/dev/null; true

# Réécrire la ligne package selon le dossier + insérer les imports wildcard inter-packages
# (insertion via awk : robuste même si le package n'est pas en ligne 1)
for pkg in core claude acp ui services settings; do
  for f in "$NEW/$pkg"/*.kt; do
    sed -i "s/^package com\.agentnav.*/package com.agentnav.$pkg/" "$f"
    awk '
      { print }
      /^package com\.agentnav\./ && !done {
        print ""
        print "import com.agentnav.core.*"
        print "import com.agentnav.claude.*"
        print "import com.agentnav.acp.*"
        print "import com.agentnav.ui.*"
        print "import com.agentnav.services.*"
        print "import com.agentnav.settings.*"
        done=1
      }
    ' "$f" > "$f.tmp" && mv "$f.tmp" "$f"
  done
done

# Renommer les 2 classes ToolWindow dans tout le code
find src -name "*.kt" -exec sed -i 's/ClaudeACPToolWindowFactory/AgentNavToolWindowFactory/g; s/ClaudeACPToolWindowPanel/AgentNavToolWindowPanel/g' {} +
sed -i 's/ClaudeACPToolWindowFactory/AgentNavToolWindowFactory/g' src/main/resources/META-INF/plugin.xml
```

- [ ] **Step 3: Purger les imports intra-racine devenus faux**

Les anciens `import com.agentnav.NomDeClasse` (package racine plat, devenus invalides après l'éclatement) sont couverts par les wildcards — les supprimer :

```bash
find src -name "*.kt" -exec sed -i '/^import com\.agentnav\.[A-Z][A-Za-z]*$/d; /^import com\.agentnav\.core\.[A-Z]/d; /^import com\.agentnav\.backend\./d; /^import com\.agentnav\.acp\.[A-Z]/d' {} +
```

- [ ] **Step 4: Compiler et corriger les résidus**

Run: `./gradlew compileKotlin --no-daemon`

Corriger itérativement toute erreur restante (références FQN dans des strings, `com.intellij.openapi.options.ShowSettingsUtil...AgentSettingsConfigurable::class.java` dans PromptInputPanel, etc.) jusqu'à `BUILD SUCCESSFUL`. Les erreurs attendues sont des références qualifiées complètes dans le code (rechercher : `grep -rn "com\.agentnav\.[A-Z]" src --include="*.kt"`).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "refactor!: restructuration packages com.agentnav (core/claude/acp/ui/services/settings)"
```

---

### Task 8: Identité plugin — plugin.xml + Gradle

**Files:**
- Modify: `src/main/resources/META-INF/plugin.xml`
- Modify: `build.gradle.kts`
- Modify: `settings.gradle.kts`

- [ ] **Step 1: plugin.xml — nouvelle identité**

Remplacements exacts (le sed de Task 7 a déjà migré les FQN des classes) :
- `<id>com.agentnav.bridge</id>` → `<id>com.agentnav</id>` (le sed a transformé `com.claudeacp.bridge` en `com.agentnav.bridge`)
- `<name>AgentNav ACP</name>` → `<name>AgentNav</name>`
- `<vendor email="you@example.com" ...>` → `<vendor email="devfawzi@gmail.com" url="https://github.com/devfawzi/agent-nav-acp">DevFawzi</vendor>`
- `<toolWindow id="AgentNav ACP"` → `<toolWindow id="AgentNav"`
- `displayName="AgentNav ACP"` (configurable) → `displayName="AgentNav"`
- `id="com.agentnav.settings"` : reste tel quel (déjà correct après sed)
- Dans la description HTML : remplacer les 2 occurrences « AgentNav ACP » par « AgentNav » ; remplacer « Tools → AgentNav ACP » par « Tools → AgentNav ».
- FQN à corriger (le sed de Task 7 n'a changé que la racine, pas les sous-packages) — liste exhaustive :
  - `factoryClass="com.agentnav.ui.AgentNavToolWindowFactory"`
  - `<projectService serviceImplementation="com.agentnav.services.PromptHistoryService"/>`
  - `<projectService serviceImplementation="com.agentnav.services.PendingChangesService"/>`
  - `<projectService serviceImplementation="com.agentnav.services.DiffViewerManager"/>`
  - `<projectService serviceImplementation="com.agentnav.services.PluginLogService"/>`
  - `<applicationService serviceImplementation="com.agentnav.settings.AgentSettings"/>`
  - `<applicationService serviceImplementation="com.agentnav.services.AgentProfilesService"/>`
  - `<applicationService serviceImplementation="com.agentnav.services.DiffPaletteService"/>`
  - `instance="com.agentnav.settings.AgentSettingsConfigurable"`
  - `class="com.agentnav.services.AddSelectionToChatAction"` (id `com.agentnav.AddSelectionToChat`)
- Ne PAS ajouter `AcpProcessHub` ni `MemoryPathsCache` à plugin.xml : ce sont des light services (`@Service`), auto-enregistrés — les déclarer en double lèverait un warning verifyPlugin.

- [ ] **Step 2: Gradle**

`build.gradle.kts` : `group = "com.claudeacp"` → `group = "com.agentnav"` ; `version = "0.1.0"` → `version = "0.9.0"`.
`settings.gradle.kts` : `rootProject.name` → `"agentnav"`.

- [ ] **Step 3: Build complet du zip**

Run: `./gradlew buildPlugin --no-daemon`
Expected: `BUILD SUCCESSFUL` + zip dans `build/distributions/agentnav-0.9.0.zip`

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat!: identité AgentNav — pluginId com.agentnav, tool window AgentNav, v0.9.0"
```

---

### Task 9: Nettoyage documentaire

**Files:**
- Modify: `README.md`, `CONTEXT.md` (références « AgentNav ACP », « claude-acp-plugin », chemins sandbox)

- [ ] **Step 1: Mettre à jour README.md**

Titre et descriptions : « AgentNav — Claude Code & ACP agents for JetBrains IDEs ». Corriger les instructions d'install (nom du zip `agentnav-0.9.0.zip`), l'id de tool window, le chemin Settings → Tools → AgentNav. Mentionner Claude Code par défaut + agents ACP (OpenCode, customs) en secondaire.

- [ ] **Step 2: Marquer CONTEXT.md comme historique**

Ajouter en tête de CONTEXT.md : `> ⚠️ Document historique (pré-rework 2026-07-06). Architecture courante : voir docs/superpowers/specs/2026-07-06-agentnav-rework-design.md.`

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "docs: README AgentNav + CONTEXT marqué historique"
```

---

### Task 10: Smoke test sandbox (gate final du plan)

- [ ] **Step 1: Lancer la sandbox**

Run: `cd /home/fawzi/Dev/agent-nav-acp && ./gradlew runIde --no-daemon` (en background ; l'user teste, puis ferme).

- [ ] **Step 2: Checklist smoke (validation user)**

1. Tool window « AgentNav » visible, onboarding absent (claude installé).
2. Nouveau chat = Claude Code par défaut ; prompt simple → réponse streamée.
3. `ps aux | grep "claude --"` pendant 2 tabs ouverts → 2 process distincts.
4. Prompt Write/Edit → FileChangeCard + diffViewer + Pending Changes.
5. Chat OpenCode (switch agent avant 1er prompt) → réponse ; prompt Write → **card permission Allow/Deny s'affiche** (nouveau comportement ACP) ; Allow → diff visible.
6. 2 tabs Claude + 1 tab OpenCode en parallèle, prompts croisés → aucun cross-talk.
7. Fermer un tab OpenCode puis le dernier → `ps aux | grep opencode` → process killé.
8. Action « Inspect Claude Memory » après un 1er prompt Claude → paths listés (via MemoryPathsCache).

- [ ] **Step 3: Corriger les éventuels bugs découverts, re-tester, puis merger**

Quand la checklist passe (confirmation user) :

```bash
git checkout main && git merge --no-ff rework/agentnav -m "merge: rework AgentNav plan 1 — big bang structure"
```

---

## Suites (plans séparés, dans l'ordre du spec)

- **Plan 2 — Protocole** : extraction `ClaudeStreamParser`/`ClaudeRequests` depuis ClaudeCliBackend, harness de capture, fixtures claude 2.1.201, tests JUnit, investigation rename de session.
- **Plan 3 — Sessions** : resume in-tab + replay historique, picker cross-projets, rename bidirectionnel.
- **Plan 4 — Distribution** : CI GitHub Actions, release pipeline, `updatePlugins.xml` sur gh-pages, v0.9.0 installée sur l'IDE réel.
