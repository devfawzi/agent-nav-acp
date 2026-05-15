# AgentNav ACP — Contexte complet pour reprise de chat

## Projet
Plugin IntelliJ qui intègre Claude Code (et OpenCode) dans l'IDE avec une UX Cursor-like.
- **Repo de travail** : `/home/fawzi/Dev/HQDP/claude-acp-plugin/` (sandbox dev)
- **Repo git** : `/home/fawzi/Dev/agent-nav-acp/` (poussé sur `origin/main`)
- **Plateforme** : IntelliJ Platform SDK 2026.1.1, Kotlin 2.3.21, JDK 21, Gradle 9.0
- **OS** : Linux (sandbox `.intellijPlatform/sandbox/`)
- **User mail** : devfawzi@gmail.com

## Architecture cible et état actuel

### Transport pluggable (2 modes)
Le service principal `ClaudeACPService` gère 2 transports selon le profile :

1. **`Transport.CLI_STREAM_JSON`** (Claude Code) — **transport actif depuis le pivot**
   - Spawn `claude --output-format stream-json --input-format stream-json --verbose --permission-mode acceptEdits` direct
   - Préfixé par `stdbuf -oL -eL` pour line-buffering (sinon claude bufferise stdout sur pipe → events coincés)
   - Multi-process natif : **1 process claude par chat tab** (lazy via `newSession()`)
   - Utilise plan d'abonnement interactif Claude (pas l'API)

2. **`Transport.ACP`** (OpenCode, agents custom)
   - Protocole JSON-RPC classique : `initialize` → `session/new` → `session/prompt` → `session/update`
   - 1 process, N sessions concurrentes
   - OpenCode : `npx -y opencode-ai acp`

### Pourquoi ce pivot ?
Depuis le 15 juin 2026, **Anthropic facture Agent SDK + `claude -p`** sur un crédit séparé au tarif API. Le mode bidirectionnel `--input-format stream-json` sans `-p` reste sur le plan interactif (à confirmer définitivement après 15 juin). Si Anthropic clarifie que c'est aussi facturé, fallback envisageable : hooks HTTP + terminal embarqué (perd ~30% de l'UX).

## Fichiers Kotlin clés

```
src/main/kotlin/com/claudeacp/
├── ClaudeACPService.kt        # 1700+ lignes — service projet, gère les 2 transports
├── ChatPanel.kt               # UI chat avec markdown, thinking, tool cards, plan preview
├── PromptInputPanel.kt        # Input avec attach/@/drag&drop/dropdowns Model/Mode/Effort
├── ClaudeACPToolWindowPanel.kt # Orchestrateur d'un chat (sid, claim, listeners)
├── ClaudeACPToolWindowFactory.kt # Factory multi-chat (NewChat/RenameChat actions)
├── AgentProfile.kt            # Data class + builtin profiles + Transport enum
├── AgentProfilesService.kt    # Persistence des custom profiles
├── AgentBinaryResolver.kt     # 4-tier resolution (env → settings → candidates → which)
├── AgentSettings.kt           # Persistence paths claude/npx/opencode
├── AgentSettingsConfigurable.kt # Settings page (Tools → AgentNav ACP)
├── AgentConnectionTester.kt   # Test connection bouton dans settings
├── OnboardingPanel.kt         # Onboarding si claude pas installé
├── PendingChangesService.kt   # Map<path, PendingChange> avec triggeredBySessionId
├── PendingChangesPanel.kt     # Bar pliable au-dessus de l'input
├── DiffViewerManager.kt       # ChainDiffVirtualFile + showDiffForFile
├── PromptHistoryService.kt    # Historique par session, capture before/after
├── FileMentionPopup.kt        # @ autocomplete via JBPopup
├── AttachmentChip.kt          # Chip UI pour les pièces jointes
└── PromptAttachment.kt        # data class FileLink | Image (base64)
```

## CLI stream-json — détails techniques importants

### Spawn (`spawnClaudeCli`)
```
stdbuf -oL -eL /home/fawzi/.local/bin/claude
  --output-format stream-json
  --input-format stream-json
  --verbose
  --permission-mode acceptEdits
  --permission-prompt-tool stdio
  [--resume <sid> | --session-id <uuid>]
  [--mcp-config <path>] [--model <id>] [--effort <level>]
```
- **PAS de `-p / --print`** : malgré ce que dit `claude --help`, test direct (2.1.123, 2026-05-15) prouve que bidirectionnel stream-json + control_request (set_model, set_permission_mode, interrupt) + permission-prompt-tool stdio + --resume fonctionnent tous SANS `-p`.
- **Pourquoi c'est critique** : à partir du 15 juin 2026, `claude -p` consomme un bucket "Agent SDK credit" séparé (~$200/mois sur Max 20x puis pay-as-you-go API). Sans `-p`, le plugin reste sur le tarif subscription interactif standard (comme l'usage terminal/IDE classique).
- `--permission-prompt-tool stdio` route les permission requests via `sdk_control_request` subtype:"permission" pour qu'on affiche une carte Allow/Deny au lieu du blocage silencieux.
- `stdbuf -oL -eL` force line-buffer stdout+stderr.
- `NO_COLOR=1`, `TERM=dumb` env pour éviter codes ANSI.
- `--resume <sid>` fonctionne avec stream-json bidirectionnel. UI : action "Resume Previous Chat" via `ClaudeSessionsService` + `ResumeSessionDialog`.

### Events reçus sur stdout (NDJSON, 1 event par ligne)
- `{"type":"system","subtype":"init", session_id, model, permissionMode, tools, ...}` — au démarrage de la session (arrive APRÈS le 1er user message en mode persistent)
- `{"type":"system","subtype":"session_state_changed", state:"running"|"idle", session_id}` — état de la session
- `{"type":"assistant","message":{content:[{type:"text"|"thinking"|"tool_use", ...}]}, session_id}`
- `{"type":"user","message":{content:[{type:"tool_result", tool_use_id, ...}]}, session_id}`
- `{"type":"result","subtype":"success","is_error":false,"result":"...", "total_cost_usd", session_id}`
- `{"type":"rate_limit_event", rate_limit_info, session_id}` — ignoré
- `{"type":"control_response", request_id, response:{subtype:"success"|"error", ...}}` — réponse à nos control_request

### Events envoyés sur stdin
**User message** :
```json
{"type":"user","message":{"role":"user","content":[
  {"type":"text","text":"..."},
  {"type":"image","source":{"type":"base64","media_type":"image/png","data":"..."}}
]}}
```
Pour `@file` mentions : on injecte `@/abs/path` dans le texte, claude utilise `Read`.

**Control requests** (switch model/mode/effort in-place sans respawn — **TESTÉS, FONCTIONNENT avec `-p`**) :
```json
{"type":"control_request","request_id":"set-model-<ts>","request":{"subtype":"set_model","model":"claude-sonnet-4-6"}}
{"type":"control_request","request_id":"set-mode-<ts>","request":{"subtype":"set_permission_mode","mode":"plan"}}
{"type":"control_request","request_id":"interrupt-<ts>","request":{"subtype":"interrupt"}}
```
- `set_effort` est **rejeté** par claude stream-json ("Unsupported control request subtype") → respawn avec `--effort <level>`.
- `interrupt` arrête la génération sans tuer le process (utilisé par cancelCliPrompt).
- Réponse claude : `{"type":"control_response","response":{"subtype":"success","request_id":"..."}}` ou `{"subtype":"error","error":"..."}`.
- Update UI optimiste avec rollback : `pendingControlRequests` mémorise un callback pour revertir si claude répond error.
- Après set_permission_mode, claude émet `{"type":"system","subtype":"status","permissionMode":"..."}` puis ré-émet `system:init` — source de vérité.

**Permission requests** (--permission-prompt-tool stdio) :
```json
{"type":"sdk_control_request","request":{"subtype":"permission","request_id":"perm_1","tool_name":"Bash","tool_input":{"command":"rm -rf /tmp/test"}}}
```
Réponse : `{"type":"control_response","response":{"subtype":"success","request_id":"perm_1","response":{"behavior":"allow"}}}` ou `{"behavior":"deny","message":"..."}`. UI : `PermissionRequestCard` (carte jaune Allow/Deny inline).

### Multi-chat (CLI mode)
- Chaque chat = 1 process claude indépendant
- Map `cliProcesses: Map<sessionId, CliProc>` (claim après system:init)
- Pendant le 1er prompt, le proc est dans `pendingCliProcs` (sid pas encore reçu)
- Placeholder sid `"pending-<timestamp>"` utilisé pour `historyService.startPrompt` → remap vers vrai sid quand system:init arrive (via `PromptHistoryService.remapCurrentSessionId`)

### Routing chunks/tools per chat
- `ClaudeACPToolWindowPanel.matchesMySession(chunkSid)` strict : `mySessionId == chunkSid`
- Chat 1 (isFirstChat=true) claim le 1er sid émis via `sessionCreatedListener`
- Chat 2+ via factory : `acpService.newSession { setSessionId(it) }`
- Agent switch : `agentSwitchedListener` reset mySessionId, Chat 2+ schedule un postReadyNewSession

## Plan mode (UX spéciale)

En `--permission-mode plan`, claude émet `tool_use Write/Edit/MultiEdit` mais **n'écrit pas sur disque** (mode read-only). Solution :

- `ToolCallInfo` étendu avec `writeContent`, `editOldString`, `editNewString`, `permissionMode`
- En CLI mode, `handleCliToolUse` populate ces champs depuis `input` du tool_use
- `ChatPanel.appendToolCall` détecte `permissionMode == "plan"` + write/edit avec contenu → affiche `PlanPreviewCard`
- Card jaune pâle 📋 avec bouton "Open preview" → ouvre le contenu dans un `LightVirtualFile` (scratch IntelliJ) navigable

## Features UX préservées (toutes opérationnelles)

- ✅ Chat custom (markdown via commonmark-java, JEditorPane HTML)
- ✅ Bulles user (bleu, droite) + assistant directes (gauche)
- ✅ ThinkingBlock collapsible (🧠)
- ✅ ToolCallsBlock compact (🔧)
- ✅ RunCommandBlock (Running ⏳ → Done ✓)
- ✅ FileChangeCard avec lignes +/− et View/Accept/Reject
- ✅ PlanPreviewCard pour mode plan
- ✅ Multi-tabs chats indépendants
- ✅ Lock agent button après 1er prompt (🔒)
- ✅ Drag&drop images
- ✅ Paste images (TransferHandler + AnAction Ctrl/Cmd+V)
- ✅ @ file mentions (FileMentionPopup via JBPopup)
- ✅ Attachments via 📎 file picker
- ✅ Drag&drop fichiers (text/uri-list)
- ✅ Bouton Stop/Cancel pendant exécution
- ✅ Auto-rename chat depuis 1er prompt (40 chars)
- ✅ Dropdowns Model/Mode/Effort (CLI : via control_request, in-place)
- ✅ Pending Changes panel (Accept All / Reject All / per-file)
- ✅ DiffViewer natif IntelliJ via ChainDiffVirtualFile (navigation `>>` hunks)
- ✅ Historique prompts cliquable (View / vs prompt précédent)

## Bugs résolus récemment

1. **OpenCode VFS pas trigger les modifs** → `event.file.contentsToByteArray()` renvoyait du cache obsolète → switch sur `java.io.File(path).readText(Charsets.UTF_8)` direct
2. **Stale `processTerminated` pendant switchAgent** → écrasait state=ERROR. Fix : compare `newHandler !== processHandler`
3. **Chats mélangés** : fallback dangereux `chunkSid == acpService.sessionId` retiré, Chat 1 claim via state listener au lieu de lazy
4. **Per-session config** : `sessionConfigs: Map<sid, SessionConfig>` au lieu d'un global ; setModel/setMode/setConfigOption prennent un `targetSessionId`
5. **Effort dropdown vide** : populé via `CLAUDE_EFFORT_OPTION` hardcoded list
6. **Historique sid placeholder cassé** : `PromptHistoryService.remapCurrentSessionId(newSid)` ajouté
7. **Plan mode write invisible** : `PlanPreviewCard` avec `LightVirtualFile`

## Bugs/limites connus

- **--resume incompatible stream-json sans --print** : on perd la conv si on doit respawn (mais on respawn plus depuis le control_request)
- **Effort/Model/Mode control_request schemas** : devinés, à confirmer au runtime via logs (`grep "control_response error"`)
- **Cache gradle transforms corrompu** régulièrement sur Linux → `rm -rf ~/.gradle/caches/9.0.0/transforms/<hash>` + rebuild
- **Plugin officiel agent SDK Anthropic** : si Anthropic clarifie après 15 juin que stream-json bidirectionnel est facturé, fallback nécessaire (hooks HTTP + terminal embarqué)
- **Single-process global** : switcher d'agent (Claude ↔ OpenCode) kill tous les chats actifs → warning dialog Yes/No avant
- **inotify limit Linux** : VFS rate parfois les modifs → retries (200ms, 800ms) + fallback `addOrUpdate` manuel sur status=completed

## État git

```
4078834 Claude Code via CLI stream-json (drop ACP for Claude, keep for OpenCode)
bc5eaf3 multi-agent ACP, per-session isolation, agent lock & UX polish
b7e859f amélioration ajout drag drop tag file et paste file
622cca4 init
```
Branch `main` peut être push (1 commit ahead).

## Commandes utiles

### Build & test
```bash
cd /home/fawzi/Dev/HQDP/claude-acp-plugin
./gradlew compileKotlin --no-daemon  # compile
./gradlew runIde --no-daemon         # lance sandbox IDE
```

### Debug claude CLI
```bash
# Test schéma events
echo '{"type":"user","message":{"role":"user","content":"hi"}}' | \
  claude --output-format stream-json --input-format stream-json \
         --verbose --permission-mode acceptEdits | head -20

# Voir les flags
claude --help

# Vérifier --effort
claude --help | grep -A1 effort
```

### Logs sandbox IDE
```bash
tail -f .intellijPlatform/sandbox/claude-acp-plugin/IU-2026.1.1/log/idea.log | \
  grep -E "ClaudeACPService|CLI"
```

## Préférences user (du chat précédent)

- **Réponses en français** (sauf code/identifiants)
- **Très peu de bavardage**, concis, pas de récap pré-action
- **Tableaux** appréciés pour comparer options/tradeoffs
- **Soyons direct** : si X marche pas, dire pourquoi techniquement, pas faire des "il faudrait que..."
- **Tester par sandbox IDE** : lancer `runIde` en background, l'user teste, ferme, je récupère
- **Pas de multi-process complexe inutile** : préférer simple + iterer
- **Garde la full UX** : drag&drop, @, images, multi-tabs — non négociable

## TODO potentiel (pas commencé)

- [ ] Tester définitivement si `set_model` / `set_permission_mode` / `set_effort` control_request fonctionnent vraiment ou si claude les rejette (regarder les logs après usage)
- [ ] Si rejeté : fallback respawn intelligent avec `--continue` ou conversation replay
- [ ] Multi-process Claude + OpenCode en parallèle (refactor `MultiAgentService` qu'on avait commencé puis rollback)
- [ ] Renommer `ClaudeACPService` → `AgentService` (plus représentatif maintenant qu'il gère CLI aussi) — gros refactor
- [ ] Hook HTTP fallback pour after-15-juin si stream-json facturé
- [ ] Persistance des sessions claude entre redémarrages IDE (via `--resume` si on trouve une solution)
- [ ] Status visuel quand model/mode/effort change effectivement (current display via system:init data)

## Mémoire automatique stockée

`/home/fawzi/.claude/projects/-home-fawzi-Dev-HQDP/memory/MEMORY.md` :
- `project_claude_acp_plugin.md` — POC 100% fonctionnel 2026-05-14
- `reference_acp_protocol.md` — Format ACP claude-agent-acp reverse-engineered v0.33.1
- `project_hqdp_demo_dcs_room_reduction.md` — Démo DCS à rollback
