# Plan 2 — Protocole Claude Code (parser testé, harness, fixtures)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rendre le protocole stream-json irréprochable : parsing extrait dans des unités pures testées contre des fixtures capturées sur le vrai claude 2.1.201, harness de capture rejouable, investigation du rename de session.

**Architecture:** `ClaudeStreamParser` (String → `ClaudeEvent` sealed class, zéro dépendance IntelliJ), `ToolCallMapper` (ToolUse → `ToolCallInfo` riche, pur), `ClaudeRequests` (builders des messages sortants, golden-testés). `ClaudeCliBackend` ne garde que les effets (callbacks, VFS, services). Fixtures NDJSON versionnées par version de claude — la re-capture sur une nouvelle version rend le drift de protocole visible par diff.

**Tech Stack:** Kotlin 2.3.21, JUnit 5, Gson, Python 3 (harness, stdlib uniquement).

**Spec:** `docs/superpowers/specs/2026-07-06-agentnav-rework-design.md` §4.

**Note d'exécution :** ce plan est exécuté inline dans la session qui l'a écrit (contexte complet des handlers legacy déjà lu). Les corps de code déjà existants à déplacer sont référencés par méthode source (`ClaudeCliBackend.handleXxx`) plutôt que recopiés ; les nouveaux types/squelettes sont complets.

---

### Task 1: Setup tests Gradle

**Files:** Modify `build.gradle.kts`

- [ ] Ajouter aux dépendances :

```kotlin
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(kotlin("test"))
```

et le bloc :

```kotlin
tasks.test {
    useJUnitPlatform()
}
```

- [ ] Run: `./gradlew test --no-daemon` → `BUILD SUCCESSFUL` (0 test). Commit `test: setup JUnit 5`.

---

### Task 2: `ClaudeEvent` + `ClaudeStreamParser`

**Files:** Create `src/main/kotlin/com/agentnav/claude/ClaudeStreamParser.kt`

Sealed class complète (les champs couvrent tout ce que les handlers actuels extraient) :

```kotlin
sealed class ClaudeEvent {
    data class Init(
        val sessionId: String?, val model: String?, val permissionMode: String?,
        val slashCommands: List<String>, val mcpServers: List<McpServerInfo>,
        val mcpTools: List<String>, val skills: List<String>, val agents: List<String>,
        val memoryPaths: Map<String, String>
    ) : ClaudeEvent()
    data class Status(val permissionMode: String?) : ClaudeEvent()
    /** system:hook_started / system:hook_response (claude 2.1.201+). */
    data class Hook(val subtype: String, val hookName: String?) : ClaudeEvent()
    data class SystemOther(val subtype: String?) : ClaudeEvent()
    data class AssistantText(val text: String, val isSynthetic: Boolean) : ClaudeEvent()
    data class AssistantThinking(val text: String) : ClaudeEvent()
    data class ToolUse(val id: String?, val name: String, val input: JsonObject?) : ClaudeEvent()
    data class ToolResult(val toolUseId: String, val isError: Boolean, val errorText: String?) : ClaudeEvent()
    data class TurnResult(
        val isError: Boolean, val errorMessage: String?, val totalCostUsd: Double,
        val inputTokens: Long, val outputTokens: Long,
        val cacheReadTokens: Long, val cacheCreationTokens: Long
    ) : ClaudeEvent()
    data class ControlResponse(val requestId: String?, val success: Boolean, val error: String?) : ClaudeEvent()
    data class CanUseTool(
        val requestId: String, val toolName: String, val input: JsonElement?,
        val blockedPath: String?, val permissionSuggestionsJson: String?,
        /** true si reçu au format legacy sdk_control_request. */
        val legacy: Boolean
    ) : ClaudeEvent()
    /** control_request non-permission (subtype inconnu) — répondre allow_once permissif. */
    data class UnknownControlRequest(val requestId: String?, val subtype: String?) : ClaudeEvent()
    object RateLimit : ClaudeEvent()
    object StreamEvent : ClaudeEvent()
    data class Unknown(val type: String?, val raw: String) : ClaudeEvent()
    data class ParseError(val message: String, val raw: String) : ClaudeEvent()
}

object ClaudeStreamParser {
    /** Une ligne NDJSON → 1..n événements typés (un event assistant peut contenir
     *  plusieurs blocks text/thinking/tool_use). Ne lève JAMAIS : ParseError en dernier recours. */
    fun parse(line: String): List<ClaudeEvent>
}
```

- [ ] Implémenter `parse` en déplaçant la logique d'extraction des handlers actuels de `ClaudeCliBackend` (sans les effets) : `handleEvent` (dispatch par type), `handleSystemEvent` (init/status + nouveaux hooks), `handleAssistantEvent` (text — détecter `message.model == "<synthetic>"` —, thinking, tool_use), `handleUserEvent` (tool_result + extraction errorText), `handleResultEvent` (usage/coût/erreurs), `handleControlRequest`/`handleSdkControlRequest` (can_use_tool + legacy), `handleControlResponse`. `rate_limit_event` → RateLimit, `stream_event` → StreamEvent, type inconnu → Unknown, JSON invalide → ParseError.
- [ ] Compile + commit `feat(claude): ClaudeStreamParser — événements typés, zéro dépendance IntelliJ`.

---

### Task 3: `ToolCallMapper` + `ClaudeRequests`

**Files:** Create `src/main/kotlin/com/agentnav/claude/ToolCallMapper.kt`, `src/main/kotlin/com/agentnav/claude/ClaudeRequests.kt`

- [ ] `ToolCallMapper.fromToolUse(e: ClaudeEvent.ToolUse, sessionId: String?, permissionMode: String?): ToolCallInfo` — déplacer le corps de `ClaudeCliBackend.handleToolUse` (planContent ExitPlanMode, userQuestionsJson, path/command/writeContent/editOld/editNew, `detail` par tool y compris Task/TodoWrite/mcp__*).
- [ ] `ClaudeRequests` — builders purs retournant les String JSON exactes (déplacées de sendPrompt/cancel/replyToolResult/setMode/setModel/respondPermission) :

```kotlin
object ClaudeRequests {
    fun userMessage(text: String, attachments: List<PromptAttachment>): String
    fun toolResult(toolUseId: String, content: String): String
    fun interrupt(requestId: String): String
    fun setModel(requestId: String, modelId: String): String
    fun setPermissionMode(requestId: String, modeId: String): String
    /** allow : updatedInput OBLIGATOIRE (claude 2.1+ Zod) ; allowAlways : + updatedPermissions. */
    fun permissionAllow(requestId: String, originalInputJson: String?, permissionSuggestionsJson: String? = null): String
    fun permissionDeny(requestId: String, message: String?): String
    /** Réponse permissive aux control_request inconnus (comportement actuel conservé). */
    fun allowOnceDecision(requestId: String): String
}
```

- [ ] Compile + commit.

---

### Task 4: Refactor `ClaudeCliBackend` sur parser/mapper/requests

**Files:** Modify `src/main/kotlin/com/agentnav/claude/ClaudeCliBackend.kt`, `src/main/kotlin/com/agentnav/core/AgentBackend.kt`, `src/main/kotlin/com/agentnav/ui/AgentNavToolWindowPanel.kt`

- [ ] `handleLine(line)` : `ClaudeStreamParser.parse(line).forEach { dispatch(it) }` — `dispatch` fait UNIQUEMENT les effets (état, callbacks, VFS, caches AgentSettings, PromptHistory, budget hebdo).
- [ ] Réponses synthétiques : nouveau callback `AgentBackend.onSyntheticOutput: ((String) -> Unit)?` — `AssistantText(isSynthetic=true)` y est routé (fallback `onTextChunk` si non câblé). Panel : rendu via `chatPanel.appendInfo`-style markdown (bloc gris « système », pas bulle assistant).
- [ ] `Hook` → `pluginLog.info("hooks", …)`, pas d'UI. `Unknown` → `pluginLog.warn("protocol", …)` + `onInfo` dégradé (« ⚠ unhandled event <type> »). `ParseError` → log WARN, ligne ignorée.
- [ ] `AcpSessionBackend` : ajouter `override var onSyntheticOutput` (jamais émis côté ACP).
- [ ] Compile + smoke rapide `runIde` (prompt simple + /context) + commit `refactor(claude): backend consomme ClaudeStreamParser — effets séparés du parsing`.

---

### Task 5: Harness de capture

**Files:** Create `tools/capture_fixtures.py`, Create `src/test/resources/fixtures/claude/2.1.201/*.ndjson`

- [ ] Script python3 stdlib : spawn `stdbuf -oL -eL claude --output-format stream-json --input-format stream-json --verbose --permission-mode acceptEdits --permission-prompt-tool stdio --model claude-haiku-4-5-20251001` dans un cwd jetable (`/tmp/agentnav-fixtures-<scenario>`), pilote un scénario (liste d'actions : `send <user-json>`, `wait result`, `answer-permission allow|deny`, `send-control <json>`), timeout global 90 s, dump stdout brut dans la fixture. Sanitisation : `$HOME` → `~`, uuid de session → `SESSION_ID` (regex).
- [ ] Scénarios (chacun = 1 fixture) : `simple-text` (« Reply exactly: pong ») · `thinking` · `write-file` · `edit-file` · `bash-allow` (commande inoffensive, répondre allow au can_use_tool) · `bash-deny` (répondre deny) · `plan-mode` (spawn `--permission-mode plan`, demander une modif) · `interrupt` (send prompt long puis control interrupt) · `set-model` · `set-permission-mode` · `tool-error` (Read d'un fichier inexistant) · `slash-context` · `slash-usage` · `slash-unknown` (`/nexistepas`) · `resume` (2 spawns successifs, 2e avec `--resume`).
- [ ] Lancer la capture, vérifier les 15 fixtures non vides, les committer. Commit `test(fixtures): capture claude 2.1.201 — 15 scénarios stream-json`.

---

### Task 6: Tests parser + golden requests

**Files:** Create `src/test/kotlin/com/agentnav/claude/ClaudeStreamParserTest.kt`, `src/test/kotlin/com/agentnav/claude/ClaudeRequestsTest.kt`

- [ ] `ClaudeStreamParserTest` : pour CHAQUE fixture — rejouer ligne à ligne, assert : aucune `ParseError`, aucune `Unknown` non attendue ; assertions ciblées par scénario (ex. `simple-text` contient `AssistantText("pong", isSynthetic=false)` + `TurnResult(isError=false)` ; `bash-allow` contient `CanUseTool(toolName="Bash", legacy=false)` ; `slash-context` contient `AssistantText(isSynthetic=true)` ; `write-file` contient `ToolUse(name="Write")` + `ToolResult(isError=false)`).
- [ ] `ClaudeRequestsTest` : golden strings exactes pour chaque builder (dont `permissionAllow` avec `updatedInput` = input original, et `updatedPermissions` quand suggestions présentes).
- [ ] Run: `./gradlew test --no-daemon` → tous verts. Commit `test(claude): parser validé contre les fixtures 2.1.201 + golden requests`.

---

### Task 7: Investigation rename de session (pour Plan 3)

- [ ] Sonder sur claude réel (cwd jetable) : (a) `claude --help` grep rename/name/title ; (b) `/rename Mon titre` en stream-json → réponse ? ; (c) control_request candidats (`rename_session`, `set_session_title`, `set_title`) → « Unsupported » attendu ; (d) inspecter le `.jsonl` d'une session renommée via le TUI (`claude` interactif → `/rename`) : quel event/champ apparaît ?
- [ ] Documenter le verdict dans le spec §3 (mécanisme retenu ou fallback plugin) + fixture si un mécanisme fonctionne. Commit `docs(spec): verdict investigation rename session`.

---

### Task 8: Validation + release v0.9.1

- [ ] `./gradlew test buildPlugin --no-daemon` verts ; smoke sandbox : prompt simple, `/context` rendu distinct, permission card, diff Write.
- [ ] CHANGELOG 0.9.1 + `git tag v0.9.1 && git push origin main v0.9.1` → MAJ auto dans l'IDE.
