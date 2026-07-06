# Plan 3 — Sessions (resume in-tab, replay, rename bidirectionnel)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Exigences sessions du spec §3 : resume chargé dans le tab courant avec replay de l'historique, rename de tab répercuté côté claude (`rename_session` confirmé au Plan 2). Le picker cross-projets existe déjà (`listAllSessions` + checkbox « Only this project »).

**Architecture:** `SessionHistoryLoader` (pur, claude/) parse le `.jsonl` en items User/Assistant filtrés des wrappers ; le panel gagne `resumeSessionHere()` (même mécanique que `swapAgentProfile` : close → nouvelle ChatSession avec resumeSid/cwd → rewire → replay → start). `renameSession(title)` entre dans le contrat `AgentBackend` (défaut no-op, implémenté CLI via `ClaudeRequests.renameSession`).

**Exécution inline** (session avec contexte complet). Gates : `./gradlew test` vert par task, release v0.9.3 en fin.

---

### Task 1: renameSession de bout en bout
- `AgentBackend` : `fun renameSession(title: String) {}` (défaut no-op — l'ACP n'a pas d'équivalent).
- `ClaudeCliBackend` : override → `writeLine(ClaudeRequests.renameSession("rename-<ts>", title))`.
- `ChatSession` : délégation `fun renameSession(title) = backend.renameSession(title)`.
- `AgentNavToolWindowPanel` : `renameChat()` (rename manuel) ET l'auto-rename du 1er prompt appellent `chatSession.renameSession(title)` — chaque chat du plugin a un titre lisible dans `/resume` du CLI.
- Compile + commit.

### Task 2: SessionHistoryLoader (replay)
- Create `claude/SessionHistoryLoader.kt` : `load(file: File, maxItems: Int = 200): List<Item>` avec `sealed Item { User(text), Assistant(text) }`. Filtre wrappers système (mêmes préfixes que ClaudeSessionsService), extrait user content string/array + assistant text blocks (skip thinking/tool_use/synthetic locaux). Garde les `maxItems` derniers.
- Compile + commit.

### Task 3: Resume dans le tab courant
- Panel : `fun hasConversation(): Boolean` (= hasAutoRenamed) et `fun resumeSessionHere(picked: SessionInfo)` — close session courante, `ChatSession(project, AgentProfile.CLAUDE_CODE, resumeSid=picked.sessionId, cwdOverride=picked.cwd)`, `wireBackend()`, reset UI, replay via SessionHistoryLoader (`~/.claude/projects/<encoded-cwd>/<sid>.jsonl`) rendu par `chatPanel.appendUserMessage`/`appendAssistantChunk` + header info « ⏪ resumed », `chatSession.start()`, `hasAutoRenamed = true`.
- Factory `ResumeChatAction` : panel actif sans conversation → `resumeSessionHere` + rename du content (summary ?: 1er message). Panel actif avec conversation → confirm Yes(ici)/No(nouveau tab)/Cancel. Titre = comportement actuel conservé pour le nouveau tab.
- Compile + commit.

### Task 4: Validation + release v0.9.3
- `./gradlew test buildPlugin` verts ; CHANGELOG + changeNotes ; tag `v0.9.3` + push. Smoke user : resume in-tab avec replay visible, rename tab → titre visible dans `claude` TUI `/resume`, picker « Only this project » décoché = tous les projets.
