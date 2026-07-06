# Changelog

Toutes les évolutions notables du plugin AgentNav.

## [0.9.3] — 2026-07-06

### Ajouté
- **Resume dans le tab courant** : reprendre une session la charge dans le tab actif (confirmation si une conversation y est en cours), avec **replay de l'historique** — les échanges user/assistant du `.jsonl` sont ré-affichés dans le chat (wrappers système, thinking et sorties synthétiques filtrés).
- **Rename bidirectionnel** : renommer un tab (ou l'auto-rename du 1er prompt) renomme la session côté Claude Code (`rename_session`) — visible dans `/resume` du CLI et dans le picker du plugin.
- Le titre des tabs repris utilise le `summary` généré par claude quand il existe.

## [0.9.2] — 2026-07-06

### Changé
- Le backend Claude tourne entièrement sur le parser testé par fixtures (`ClaudeStreamParser` → `dispatchEvent`) — plus aucun parsing JSON inline (−400 lignes), chaque chemin d'événement couvert par les tests.
- Les sorties des slash builtin (`/context`, `/usage`…) s'affichent comme blocs « System output » distincts des réponses du modèle.

## [0.9.1] — 2026-07-06

### Ajouté
- **Protocole testé contre le vrai CLI** : `ClaudeStreamParser`/`ToolCallMapper`/`ClaudeRequests` extraits (purs, sans dépendance IntelliJ), harness de capture rejouable (`tools/capture_fixtures.py`), 16 fixtures claude 2.1.201, 43 tests JUnit.
- **Rename de session découvert et industrialisé** : control_request `rename_session` (champ `title`), persisté dans le `.jsonl`, visible dans `/resume` du CLI — le rename bidirectionnel des tabs arrive au prochain lot.
- Nouveaux événements typés : `thinking_tokens` (jauge de contexte live), `hook_started`/`hook_response`, sorties de commandes locales.

### Corrigé
- Les événements user à `content` texte (confirmations `<local-command-stdout>` après un `set_model`) levaient une exception silencieuse — désormais affichés comme info dans le chat.

## [0.9.0] — 2026-07-06

Rework majeur « big bang » — première version distribuée via le custom plugin repository.

### Changé
- **Identité AgentNav** : pluginId `com.agentnav`, tool window « AgentNav », packages restructurés (`core` / `claude` / `acp` / `ui` / `services` / `settings`).
- **Architecture isolée** : 1 tab de chat = 1 process agent (`ClaudeCliBackend` pour Claude Code, `AcpSessionBackend` pour OpenCode et agents ACP). Suppression du service legacy à listeners globaux (−2500 lignes).
- Claude Code est l'agent par défaut (CLI `stream-json` natif — reste sur le plan d'abonnement, pas de facturation Agent SDK).

### Ajouté
- **Module ACP dédié** : `AcpProcessHub` (1 process par agent, routing par session) + permissions ACP en cards Allow/Deny (fini l'auto-accept silencieux).
- **Parité slash commands avec le TUI Claude Code** : popup `/` peuplé dès l'ouverture du chat (caches persistants + builtins), `/mcp` liste les serveurs immédiatement, `/config` ouvre les Settings (et `/config key=value` part à claude).
- `fs/write_text_file` ACP écrit réellement le fichier (contrat client respecté).

### Corrigé
- « Inspect Claude Memory » n'affichait plus les memory paths depuis la refonte de mai (cache jamais alimenté).
- Corruption du cache gradle transforms par la sandbox `runIde` (exécution depuis une installation IDE locale via `localIdePath`).
- Crash de la sandbox sous Wayland (force XToolkit).
