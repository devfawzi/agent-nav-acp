# AgentNav — Rework big bang (design)

Date : 2026-07-06. Validé avec l'user (approche C — big bang, nom AgentNav, Claude Code par défaut, custom plugin repo avant Marketplace).

## Objectif

Transformer le plugin sandbox en produit publiable : structure définitive, Claude Code irréprochable (protocole testé contre le vrai CLI), gestion ACP isolée dans son module, mises à jour automatiques dans l'IDE de l'user via custom plugin repository. Marketplace JetBrains en phase 2, hors périmètre ici.

## Décisions actées

| Sujet | Décision |
|---|---|
| Agent par défaut | Claude Code (CLI stream-json direct, sans ACP) |
| Agents ACP | OpenCode builtin + agents custom déclaratifs, via module `acp/` dédié |
| Nom / identité | AgentNav — pluginId `com.agentnav`, package `com.agentnav` (définitif, posé avant 1ère publication) |
| Distribution | Custom plugin repo (GitHub Releases + `updatePlugins.xml`) d'abord ; Marketplace ensuite |
| Ordre | Big bang structure → tests protocole → distribution → validation v1.0.0 |

## Étape 0 — Sauvegarde (préalable obligatoire)

La sandbox `/home/fawzi/Dev/HQDP/claude-acp-plugin` a divergé du repo git `/home/fawzi/Dev/agent-nav-acp` : la refonte `AgentBackend` du 2026-05-18 (`core/`, `backend/`) n'existe que dans la sandbox, non versionnée.

1. Copier l'état sandbox → repo (uniquement `src/`, `skills/`, fichiers gradle, docs md — pas les artefacts de test `hello.txt`, `fichier*.txt`, `fawzi.txt`, `zzz`, `data.json`, `config.json`, rapports DCS, `test-repo/`).
2. Commit sur branche `pre-rework-snapshot` — point de restauration du big bang.
3. Créer `rework/agentnav` depuis ce snapshot. **Tout le rework se fait dans le repo git** ; la sandbox HQDP ne sert plus que de répertoire de test `runIde`.

## 1. Identité & structure des packages

Rename global : pluginId `com.agentnav`, nom affiché « AgentNav », package racine `com.agentnav`, tool window « AgentNav ».

```
com.agentnav/
├── core/       # contrats purs, zéro dépendance UI : AgentBackend, AgentEvents
│               # (AgentState, SessionConfig, UsageStats, ToolCallInfo, PermissionRequest),
│               # ChatSession, ChatFonts
├── claude/     # ClaudeCliBackend, ClaudeStreamParser (nouveau), ClaudeRequests (nouveau),
│               # ClaudeSessionsService (resume .jsonl)
├── acp/        # AcpProcessHub (nouveau), AcpSessionBackend (nouveau), agents builtin
├── ui/         # ChatPanel, PromptInputPanel, ToolWindowFactory/Panel, OnboardingPanel,
│               # PendingChangesPanel, dialogs (Resume, MemoryInspector, DiffCustomPalette,
│               # PluginLogDialog), popups (FileMention, SlashCommand), AttachmentChip
├── services/   # PendingChangesService, DiffViewerManager, DiffActions, DiffPaletteService,
│               # PromptHistoryService, PluginLogService, AgentProfilesService,
│               # AgentBinaryResolver, EditorSelectionGrabber, EditorDiagnosticsGrabber,
│               # AddSelectionToChatAction
└── settings/   # AgentSettings, AgentSettingsConfigurable, AgentConnectionTester
```

Sort du code :

- **`ClaudeACPService.kt` (2532 lignes) : supprimé.** La partie CLI vit déjà dans `ClaudeCliBackend` ; la partie ACP part dans `acp/` ; la glue à listeners globaux + filtres par sessionId disparaît (le modèle « 1 panel = 1 ChatSession = 1 backend » devient le seul chemin).
- `AgentProfile` / `Transport` : restent dans `core/` (modèle pur).
- `ChatPanel` (1518 l.) et `PromptInputPanel` (1163 l.) sont déplacés tels quels ; si le déplacement rend naturel d'extraire les cards (`PlanPreviewCard`, `PermissionRequestCard`, …) dans `ui/cards/`, le faire — sans réécriture de logique.
- Claude Code = profil par défaut : sélecteur d'agent, nouveau chat, onboarding pointent sur lui ; OpenCode reste sélectionnable.
- `plugin.xml` réécrit : ids d'actions, tool window id, services — plus aucune référence `com.claudeacp` / « ACP » dans les ids.

## 2. Module ACP

Le contrat `AgentBackend` ne change pas : l'UI ne distingue pas Claude d'un agent ACP.

- **`acp/AcpProcessHub.kt`** — service projet. Possède les process ACP (1 par binaire, clé = profile id, spawn lazy). Fait le handshake `initialize`, crée les sessions (`session/new`), route chaque `session/update` vers le backend propriétaire du sessionId. Réutilise la plomberie JSON-RPC existante de `ClaudeACPService` (writer stdin, reader NDJSON, map des requêtes pendantes). Refcount des sessions : dernier backend fermé → kill du process.
- **`acp/AcpSessionBackend.kt`** — implémente `AgentBackend`. Tranche isolée par session : traduit les `session/update` en callbacks (`onTextChunk`, `onToolCall`, …). Les demandes de permission ACP (`session/request_permission`) passent par `onPermission` → même card Allow/Deny que Claude (aujourd'hui auto-acceptées : corrigé).
- **Crash du process hub** : tous les backends rattachés reçoivent `onError` + état `ERROR` ; le hub respawn à la prochaine demande de session. Pas de reconnexion silencieuse côté ACP (les sessions ACP ne sont pas résumables comme claude).
- Un agent ACP = un `AgentProfile` (command, args, env, transport=ACP). OpenCode fourni en builtin ; l'user en déclare d'autres via Settings sans toucher au code.

## 3. Claude Code irréprochable

### Parser extrait et testable

- **`claude/ClaudeStreamParser.kt`** — pur Kotlin (aucune dépendance IntelliJ) : `parse(line: String): ClaudeEvent`. `ClaudeEvent` = sealed class : `Init`, `SessionStateChanged`, `Status`, `AssistantText`, `AssistantThinking`, `ToolUse`, `ToolResult`, `TurnResult`, `ControlResponse`, `CanUseTool`, `LegacyPermission`, `RateLimit`, `Unknown(raw)`. `ClaudeCliBackend` consomme des `ClaudeEvent`, plus de parsing inline.
- **`claude/ClaudeRequests.kt`** — builders des messages émis : `userMessage(text, attachments)`, `setModel`, `setPermissionMode`, `interrupt`, `permissionAllow(requestId, updatedInput, updatedPermissions?)`, `permissionDeny(requestId, message)`. Un seul endroit où les schémas wire vivent.

### Harness de capture (le « ouvrir un claude et tester des prompts » industrialisé)

- `tools/capture-fixtures.sh` : spawn le vrai `claude` en stream-json bidirectionnel dans un cwd jetable, joue des scénarios scriptés, dump le NDJSON brut dans `src/test/resources/fixtures/claude/<version>/<scenario>.ndjson`. Modèle le moins cher (haiku) et prompts minimaux pour ne pas manger le quota. Sanitisation des paths (`$HOME` → placeholder).
- Scénarios (~15) : texte simple · thinking · Write · Edit · Bash avec `can_use_tool` (allow, deny, allow-always avec `updatedPermissions`) · plan mode + ExitPlanMode · interrupt · resume `--resume <sid>` · `set_model` · `set_permission_mode` · tool_result `is_error` · MCP tool call · sub-agent (Task) · AskUserQuestion · TodoWrite · usage/cost (`result`).
- **Revalidation 2.1.201** : le protocole a été validé sur 2.1.123 ; la première capture sert d'audit des schémas actuels (control_request, `can_use_tool`, `updatedInput`).
- À chaque montée de version claude : re-capture dans un nouveau dossier `<version>/`, diff avec la version précédente → le drift de protocole est visible avant d'exploser chez les users.

### Tests

- JUnit5. `ClaudeStreamParserTest` : rejoue chaque fixture ligne à ligne, assertions sur la séquence d'événements typés (pas de crash, pas d'`Unknown` inattendu). `ClaudeRequestsTest` : golden JSON des requêtes émises.
- CI : les tests tournent sur les fixtures commitées — jamais de spawn claude en CI.

### Règle défensive

Événement/subtype inconnu → `ClaudeEvent.Unknown` → log structuré (PluginLogService, catégorie `protocol`) + rendu dégradé dans le chat. Jamais de crash, jamais de silence.

## 4. Distribution & mises à jour automatiques

- **`ci.yml`** : sur push/PR → `gradlew build` (compile + tests + `verifyPlugin`).
- **`release.yml`** : sur tag `v*` → `buildPlugin` → GitHub Release avec le zip en asset → régénère `updatePlugins.xml` sur la branche `gh-pages` :
  ```xml
  <plugins>
    <plugin id="com.agentnav" version="X.Y.Z"
            url="https://github.com/devfawzi/agent-nav-acp/releases/download/vX.Y.Z/AgentNav-X.Y.Z.zip">
      <idea-version since-build="..."/>
    </plugin>
  </plugins>
  ```
- Côté IDE réel : Settings → Plugins → ⚙ → Manage Plugin Repositories → ajouter l'URL raw du XML. L'IDE notifie chaque nouvelle version — MAJ en 1 clic, zéro review.
- Versioning semver, `CHANGELOG.md` (format Keep a Changelog), `patchPluginXml` injecte les changeNotes de la version courante.
- Phase 2 (hors périmètre) : Marketplace JetBrains — même pipeline + `publishPlugin` avec token, channels stable/eap.

## 5. Gestion d'erreurs (récap transversal)

| Source | Comportement |
|---|---|
| Ligne stdout non-JSON | log WARN catégorie `protocol`, ligne ignorée |
| Event inconnu | `Unknown` → log + rendu dégradé |
| Process claude mort en cours de turn | reconnect auto `--resume <sid>`, backoff 1s/5s/30s, 3 essais max, notif user (#4 existant, à valider) |
| Process hub ACP mort | `onError` + ERROR sur toutes les sessions, respawn lazy |
| control_response error | rollback de l'update UI optimiste (mécanisme existant conservé) |

## 6. Validation finale (gate v1.0.0)

1. CI verte (tests parser + golden requests).
2. Checklist smoke en sandbox `runIde` : multi-tabs isolés (Claude ×2 + OpenCode ×1 en parallèle) · diffViewer sur **toute** modif Write/Edit/MultiEdit (bug constaté le 2026-05-18) · permission cards allow/deny/always · resume picker · slash commands (`/mode` `/model` `/effort` `/skill` `/mcp` `/agent`) · export markdown · attachments/images/@mentions · stop sans perte de session.
3. Les 3 « TO TEST » d'IMPROVEMENTS.md : #4 reconnect auto (kill -9 pendant un turn), #31 cost cap hebdo, #32 sub-agents UI.
4. Tag `v1.0.0` uniquement quand tout passe.

## Hors périmètre

Marketplace JetBrains (phase 2) · conversation tree (#6) · quick actions éditeur (#7) · multi-cwd (#8) · MCP settings UI (#10) · auth flow MCP (#11) · hooks UI (#12) · code lens (#17) · annotations (#39) · voice input (#40) · plugin marketplace Claude Code (#35).

## Ordre d'exécution

1. **Étape 0** — snapshot sandbox → `pre-rework-snapshot`, branche `rework/agentnav`.
2. **Big bang structure** — rename AgentNav + packages + module `acp/` + suppression `ClaudeACPService` + Claude par défaut + plugin.xml. Gate : build vert + smoke sandbox rapide.
3. **Protocole** — extraction `ClaudeStreamParser`/`ClaudeRequests`, harness de capture, fixtures 2.1.201, tests JUnit.
4. **Distribution** — CI, release pipeline, `updatePlugins.xml`, release `v0.9.0` de rodage installée sur l'IDE réel de l'user.
5. **Validation** — checklist §6 → `v1.0.0`.
