# Feedbacks 2026-05-14 — analyse et plan d'implémentation

Liste des 10 feedbacks utilisateur, classés par dépendance/complexité. Chaque entrée :
**Cause technique** identifiée → **Fix** proposé → **Fichier(s)** impactés.

---

## #9 — Nom du fichier dans les tool cards (Read/Edit/etc.)
**Cause** : `ToolCallsBlock.addToolCall(title, path)` n'utilise `path` que pour l'icône.
Le label affiche juste `"Read"`, pas `"Read src/Foo.kt"`.

**Fix** :
- `ChatPanel.kt` → `addToolCall` formate le label : `title + " " + relativePath(path)` quand path présent.
- Pour `Bash` : afficher le début de la commande (déjà fait via `RunCommandBlock`).
- Pour `Grep`/`Glob` : afficher le `pattern` (ajouter au `ToolCallInfo`).

**Fichiers** : `ChatPanel.kt`, `ClaudeACPService.kt` (étendre `ToolCallInfo` avec `pattern`).

---

## #2 — Shift+Enter pour saut de ligne dans chat area
**Cause possible** :
- Le `KeyAdapter` ne consume PAS `Shift+Enter` (condition `!e.isShiftDown` à `PromptInputPanel.kt:201`), donc Swing devrait insérer un newline tout seul.
- Mais IntelliJ keymap peut intercepter `Shift+Enter` (mappé à "Start New Line" ou autre).
- `fileMentionPopup.handleKey(e)` peut consommer Enter même avec Shift si popup ouvert.

**Fix** :
- Enregistrer une `AnAction` IntelliJ qui force `textArea.insert("\n", caret)` sur Shift+Enter (pattern identique à `setupSmartPasteAction`).
- Vérifier que `fileMentionPopup.handleKey(e)` ignore Shift+Enter.

**Fichiers** : `PromptInputPanel.kt`, `FileMentionPopup.kt`.

---

## #3 — Scroll chat area + boutons play/stop toujours visibles
**Cause** : Quand l'utilisateur tape beaucoup de texte, le `JTextArea` du prompt grossit verticalement (sans max), pousse le footer hors écran → bouton Send invisible.

**Fix** :
- `PromptInputPanel.kt` : limiter `scrollText.preferredSize` ET ajouter `maximumSize = Dimension(Int.MAX_VALUE, 150)` au `centerStack` pour cap la hauteur. Le textArea devient scrollable au-delà.
- `ClaudeACPToolWindowPanel.kt` : s'assurer que `bottom` (BorderLayout.SOUTH) ne déborde pas.

**Fichiers** : `PromptInputPanel.kt`.

---

## #1 — Switch de mode ne fonctionne plus
**Cause** : `setCliMode` envoie `control_request` avec subtype `set_permission_mode`. Soit le schema est faux, soit claude renvoie une erreur silencieuse.
La doc CLI confirme les modes : `acceptEdits | auto | bypassPermissions | default | dontAsk | plan`.

**Fix** :
- Activer le logging des `control_response` (déjà partiellement présent à `handleCliControlResponse`).
- Vérifier le schema attendu : selon `claude-agent-sdk` source, le bon subtype peut être `setPermissionMode` (camelCase) au lieu de `set_permission_mode`.
- Fallback : respawn process avec nouveau `--permission-mode` (perd la conv mais marche à coup sûr).

**Fichiers** : `ClaudeACPService.kt` (`setCliMode`, `handleCliControlResponse`).

---

## #5 — Retrouver les chats du plugin via `claude --resume`
**Cause** : Au spawn, on laisse claude générer un UUID aléatoire. Pour retrouver une session depuis le terminal, il faut connaître ce UUID. On ne le persiste pas non plus côté plugin.

**Fix** :
- Au spawn, ajouter `--session-id <uuid>` (UUID v4 généré côté Kotlin via `UUID.randomUUID()`).
- Stocker le UUID dans `CliProc` (déjà `sessionId` field).
- Optionnel : afficher le UUID dans le header du chat avec un bouton "Copy session id" pour faciliter `claude --resume <uuid>` depuis le terminal.
- Optionnel : option `-n/--name` pour set un display name humain pour le picker `/resume`.

**Fichiers** : `ClaudeACPService.kt` (`spawnClaudeCli`).

---

## #10 — Commandes shell bloquées + #8 — Permission prompts
**Cause** : `--permission-mode acceptEdits` n'auto-accepte que les `Edit/Write/MultiEdit`. Pour `Bash`, `WebFetch`, etc., claude envoie un `control_request can_use_tool` qu'on traite actuellement de façon naïve dans `handleCliControlRequest` (toujours `allow_once`).

**Vérification** : selon les logs, le control_request arrive bien mais notre `response.decision:"allow_once"` peut être au mauvais format. Le bon format selon Agent SDK est probablement :
```json
{"response":{"behavior":"allow","updatedInput":...}}  // ou
{"response":{"behavior":"deny","message":"..."}}
```

**Fix global (couvre #8 + #10)** :
1. Refactor `handleCliControlRequest` pour distinguer les subtypes :
   - `can_use_tool` → afficher dialog Yes/No à l'user (avec preview de la commande), répondre `behavior:"allow"` ou `"deny"`.
   - Optionnel : mode "always allow" par session ou par tool name (à stocker dans `sessionConfigs`).
2. Ajouter dans Settings : `auto-accept tools` (liste) → bypass dialog pour ces tools.
3. Mode `bypassPermissions` au spawn par défaut ? **Non**, c'est l'inverse de ce que veut l'user (il veut être consulté).

**Fichiers** : `ClaudeACPService.kt` (`handleCliControlRequest`), nouveau `PermissionDialog.kt`.

---

## #4 — Stop casse le chat (kill du process)
**Cause** : `cancelCliPrompt` fait `proc.handler.destroyProcess()`. Le process meurt, la conv est perdue.

**Fix** :
- Envoyer un `control_request` `interrupt` à claude au lieu de kill. Claude doit comprendre comme dans le TUI quand on appuie Esc.
  ```json
  {"type":"control_request","request_id":"int-<ts>","request":{"subtype":"interrupt"}}
  ```
- Si claude ne supporte pas : fallback respawn avec `--resume <sid>` (testable maintenant qu'on a `--session-id` du #5).

**Fichiers** : `ClaudeACPService.kt` (`cancelCliPrompt`).

---

## #6 — Support MCP servers
**Cause** : Pas de gestion. Mais claude lit déjà sa config globale (`claude mcp add ...`) au spawn.

**Fix** :
- Vérifier qu'au spawn actuel les MCP globaux fonctionnent (test : `claude mcp list` puis tape un prompt qui les utilise).
- Optionnel : ajouter `--mcp-config <path>` paramétrable dans Settings → AgentNav ACP → "Custom MCP config file".
- Documenter dans le panel Onboarding : "Run `claude mcp add ...` to register MCP servers globally".

**Fichiers** : `AgentSettingsConfigurable.kt`, `ClaudeACPService.kt` (option `--mcp-config`), `OnboardingPanel.kt` (doc link).

---

## #7 — Support skills Claude Code
**Cause** : Skills = slash commands (`/security-review`, `/init`, etc.). Claude les détecte côté CLI quand un message commence par `/`. Donc devrait déjà marcher.

**Fix** :
- Tester : envoyer `/help` ou `/init` dans le chat et voir si claude répond.
- Améliorer UX : autocompletion `/` similaire à `@` (popup avec liste des skills disponibles, listées via `claude` config ou parse du résultat de `/help`).

**Fichiers** : nouveau `SkillsPopup.kt` (calqué sur `FileMentionPopup.kt`), `PromptInputPanel.kt`.

---

## Ordre d'implémentation suggéré

| # | Item | Effort | Risque |
|---|------|--------|--------|
| 1 | #9 nom fichier tool cards | 5min | nul |
| 2 | #3 scroll + footer fixe | 10min | nul |
| 3 | #2 Shift+Enter | 10min | faible |
| 4 | #5 --session-id | 10min | nul |
| 5 | #1 set_permission_mode (debug) | 30min | moyen (test runtime) |
| 6 | #4 stop sans kill | 30min | moyen (test runtime) |
| 7 | #10 + #8 permission flow | 1h | moyen-élevé |
| 8 | #6 MCP support | 20min | faible |
| 9 | #7 skills autocomplete | 30min | faible |

**Total estimé** : 3-4h de code + sandbox tests.
