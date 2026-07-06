# Changelog

Toutes les évolutions notables du plugin AgentNav.

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
