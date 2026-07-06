# AgentNav ACP — pistes d'amélioration

Brainstorm 2026-05-15. À benchmarker / valider / arbitrer avec l'user. Ce n'est pas une roadmap engagée, juste un menu d'options classées par impact perçu.

## ⏳ À tester (en attente de validation user)

Implémenté techniquement mais pas encore validé visuellement / fonctionnellement en sandbox.
À passer en ✅ DONE quand l'user a confirmé que ça marche en conditions réelles.

- **#4 Reconnect auto sur crash** — simuler un crash claude (`kill -9` du process pendant un turn) puis relancer un prompt. Doit voir notif "Claude reconnected" et reprendre.
- **#31 Cost cap hebdo** — set Settings → Weekly budget à \$0.10 → envoyer 2-3 prompts → voir warning à 80% puis confirmation à 100%. Bouton "Reset week counter" qui RAZ.
- **#32 Sub-agents UI** — `/agent` → submenu avec Explore/Plan/general-purpose → click → texte inséré dans le textarea.

## ✅ Déjà livré

### Vague du 2026-05-16
- **Code references on paste** (Cursor-like) — paste de code copié depuis un éditeur → chip `file.kt:23-45`. Action keymap `Ctrl+Alt+L` "Add selection to chat".
- **#20** Export chat → Markdown — bouton ⬇ à côté de History qui dump la conv en .md (wrappers strippés, tool calls résumés).
- **#26** LSP diagnostics auto-injectés — au prompt, append les errors/warnings du buffer ouvert. Toggle Settings (errors only / + warnings).
- **#37** Memory paths inspector — action title-bar qui liste les .md de mémoire auto + open/delete.
- **#43** Logger panel — action title-bar avec dialog filtrable (level, category) + clear/copy. Tracé control_request/response, state changes.
- **DiffViewer custom toolbar** — Accept all / Reject all / Customize colors. Hunk-by-hunk granulaire via ⮜⮞ natifs IntelliJ.
- **Diff palettes** (Pastel/Soft/Vivid/HighContrast/Custom) avec live preview dialog
- **Settings shortcut** — action ⚙ dans la title-bar de la tool window.
- **#36** Output style switcher — **abandonné** : `set_output_style` non supporté par claude 2.1.123 (test : "Unsupported control request subtype").
- **#4** Reconnect auto sur crash claude — respawn via `--resume <sid>` avec backoff (1s, 5s, 30s) limité à 3 essais.
- **#31** Cost cap & weekly budget — Settings UI : budget hebdo $, soft warning 80%, hard stop 100% avec confirmation. Reset auto chaque lundi 00:00.
- **#32** Sub-agents UI — slash command `/agent` avec submenu listant Explore / Plan / general-purpose / custom user agents. Click = insère un prompt template.

### Vague du 2026-05-15
- **#1** Inline diff dans le chat — clic sur la card file expand un diff line-by-line avec couleurs voyantes rouge/vert.
- **#2** Apply hunk-by-hunk — chaque hunk a une checkbox, boutons *Apply selected* / *Apply all* / *Reject all*. Reconstruction du fichier via `PendingChangesService.applyPartial`.
- **#3** Token/coût discret — petit label à côté de History (`1.2k tok · $0.05`), tooltip avec détails (input/output/cache).
- **#5** Indicateur live d'activité — bandeau au-dessus du chat (`📖 Read MyFile.kt`, `⚙ Bash: npm test`, `🤔 Thinking…`, `✍ Writing reply…`).
- **#44** Resume picker — skip wrappers (`<local-command-caveat>`, `<system-reminder>`, `<local-command-stdout>`, …), affichage first + last user message avec dates, utilisation du `summary` si présent.
- **#45** Slash commands `/mode` `/model` `/effort` `/Yskill` `/mcp` — interceptés par le plugin, ouvrent une `SlashPickerCard` interactive dans le chat. Dropdowns du toolbar supprimés.
- **#46** Skill Python `extract-context` — parse un .jsonl en markdown propre (wrappers/thinking/tool noise strippés). Options `--last N`, `--since YYYY-MM-DD`. Installer `skills/install.sh`.
- **#47** Input panel redesign — boutons icon-only 28×28 (+ et ▶/⏹), footer compact, hauteur textarea ÷2.

## ⏳ Restant (à arbitrer)
Voir détails par section ci-dessous. L'user a déjà filtré pendant la revue précédente (#9, #15, #16, #18-19, #21-25, #27-30, #33-34, #38, #41-42 ont été écartés).

## 🚀 Impact fort, effort raisonnable

### 1. Inline diff dans le chat (Cursor-like) ✅ DONE (2026-05-15)
**État actuel** : un clic sur "View" ouvre le diff IntelliJ dans un onglet séparé.
**Proposition** : sous chaque `FileChangeCard`, afficher un diff inline collapsible avec syntax highlighting (TextMate via `LightVirtualFile` + `EditorTextField` IntelliJ). Click `+12 -3` → expand/collapse.
**Effort** : moyen (1-2 jours). On a déjà `before` et `after` dans `PendingChange`.
**Risque** : performance si beaucoup de file changes par turn — limiter à N premières lignes ou virtualiser le scroll.
Oui ca peut etre magnifique mais la diff est dans le chat ou dans le viewEditor ?

### 2. Apply hunk-by-hunk ✅ DONE (2026-05-15)
**État actuel** : Accept/Reject est tout-ou-rien par fichier.
**Proposition** : parser le diff en hunks (via `com.intellij.diff.tools.fragmented.LineFragmentBuilder` ou diff-match-patch lib), afficher chaque hunk avec checkbox, Accept selected → reconstruire le fichier avec seulement les hunks cochés.
**Effort** : moyen-haut (2-3 jours). Algo OK, mais UX à soigner pour ne pas noyer l'user.
**Risque** : si claude attend les changes via le tool_result, un partial accept peut confondre claude pour le turn suivant.
Tres bien ca mais ca doit etre sur la ViewEditor avec une diff rouge voyant et vert voyant 


### 3. Token / coût en statusbar ✅ DONE (2026-05-15)
**État actuel** : aucun affichage.
**Proposition** : statusbar widget IntelliJ avec `input/output tokens` cumulés sur la session courante + `$ total` + countdown jusqu'à reset 5h. Les events `usage` et `total_cost_usd` sont déjà dans le stream NDJSON.
**Effort** : faible (4-6h). Surtout du plumbing UI.
**Risque** : aucun, sauf bruit visuel si trop chargé.
Tres discret juste a coté de history en haut a droite en petit

### 4. Reconnect automatique sur crash claude ⏳ TO TEST (2026-05-16 — pending user validation)
**État actuel** : si claude crashe en plein turn, le proc disparaît et l'user voit "No Claude CLI process available" au prochain prompt.
**Proposition** : dans `processTerminated`, si `executingSessionId != null` ET exit != 0 ET sid claimé, auto-respawn avec `--resume <sid>` après backoff (1s, 5s, 30s). Notify l'user "Claude reconnected".
**Effort** : moyen (1 jour, plus tests edge cases).
**Risque** : boucle infinie si claude crash à chaque boot — limiter à 3 essais.

## 🎨 Impact UX

### 5. Indicateur live d'activité ✅ DONE (2026-05-15)
**État actuel** : Thinking est dépliable mais rien ne signale qu'un tool tourne.
**Proposition** : bandeau au-dessus du chat pendant l'exécution avec le tool en cours (`🔎 Reading file.kt`, `⚙ Running pytest…`, `🤔 Thinking…`). Mis à jour via les events `tool_use` + `tool_result`. Disparait sur `result`.
**Effort** : faible (4-5h).
**Risque** : aucun.

### 6. Conversation tree / branches ✅ DONE (2026-07-06 — /tree : arbre parentUuid + fork via --fork-session)
**État actuel** : un seul fil linéaire. Si on edit un prompt précédent, on perd l'historique d'après.
**Proposition** : exposer l'arbre de la conv (claude stocke `parentUuid` dans les .jsonl) — sidebar collapsible avec tree view, click sur une branche = rejoue.
**Effort** : élevé (3-5 jours). UI à concevoir, modèle de données complet à parser.
**Risque** : peut alourdir l'UI si pas bien intégré. À évaluer si l'user en a vraiment besoin.
c'est utile

### 7. Quick actions sur la sélection éditeur ✅ DONE (2026-07-06 — Ask/Explain/Refactor with Claude)
**État actuel** : il faut copier-coller dans le chat.
**Proposition** : right-click dans n'importe quel éditeur → "Ask Claude about this", "Refactor with Claude", "Explain with Claude". Pré-remplit le prompt + ouvre le tool window.
**Effort** : faible (4h). C'est juste 3 actions IntelliJ + un peu de format.
**Risque** : aucun.

### 8. Multi-cwd / @path absolu ✅ DONE (2026-07-06 — Settings → Additional directories → --add-dir)
**État actuel** : `@file` ne propose que les fichiers du projet courant.
**Proposition** : `@/abs/path` toujours autorisé ; et un toggle "Add directory" qui passe `--add-dir <path>` à claude pour étendre son sandbox.
**Effort** : faible (2-3h).
**Risque** : claude peut écrire ailleurs, l'user doit être conscient.

## 🔧 Impact technique / robustesse


### 10. Settings UI pour MCP
**État actuel** : édition manuelle d'un `.mcp.json` hors plugin.
**Proposition** : Settings → AgentNav ACP → MCP → table avec colonnes (Name, Type stdio/http, Command/URL, Env, Status). "+ Add" ouvre un form structuré. Génère le `.mcp-config.json` automatiquement.
**Effort** : moyen (1-2 jours).
**Risque** : aucun.

### 11. Auth flow MCP needs-auth
**État actuel** : click sur 🔑 insère un prompt textuel, claude doit invoquer le tool `mcp__server__authenticate` manuellement.
**Proposition** : flow custom — click 🔑 → ouvre le navigateur sur l'URL d'auth + callback local sur port aléatoire → token capturé → écrit dans la config du MCP. Pour les MCP OAuth classiques.
**Effort** : élevé (2-3 jours pour le générique). À limiter à 1-2 providers populaires d'abord (Gmail, Notion).
**Risque** : varie par provider, sécurité du callback local à soigner.
Mcp doit plus avoir une liste mais un /mcp qui me liste dans le chat celui que je veux

### 12. Hooks Claude Code exposés en UI
**État actuel** : les hooks (`PreToolUse`, `PostToolUse`, `Stop`) sont configurables via `settings.json` directement.
**Proposition** : Settings → AgentNav ACP → Hooks → table de hooks par event type, avec script/command. Génère et merge dans `.claude/settings.json`.
**Effort** : moyen (1-2 jours).
**Risque** : assez niche, intéresse surtout les power users.
A quoi servent les hook claude code ?

## 📦 Impact distribution

### 13. JetBrains Marketplace publishing
**État actuel** : install manuelle par zip.
**Proposition** : compléter `pluginConfiguration` (icon, screenshots, categories), créer compte vendor verified JetBrains, publier sur le Marketplace.
**Effort** : faible côté code (review du plugin.xml), modéré côté process (review JetBrains).
**Risque** : la review JetBrains peut demander des changements ou refuser si pas signé.
A faire en dernier

### 14. Auto-update via channels
**État actuel** : install statique d'une zip.
**Proposition** : publier sur un repo plugin (custom ou Marketplace) avec channels `stable` / `eap`. Auto-update IntelliJ s'occupe du reste.
**Effort** : dépend du Marketplace publishing (cf #13).
**Risque** : aucun.
A faire en dernier
## 💎 Bonus — productivité avancée


### 17. Code lens inline "Explain · Refactor · Test"
Au-dessus de chaque méthode/classe (via `CodeVisionProvider` IntelliJ), 3 actions claude one-click qui pré-remplissent un prompt ciblé et l'envoient direct.
*Effort : faible-moyen (1j). Risque : visuellement chargé si trop d'actions.*


### 20. Export chat → Markdown / HTML ✅ DONE (2026-05-15)
Action "Export this conversation" → fichier `.md` propre (prompts + réponses + tool calls résumés + fichiers modifiés liés). Utile pour PR description, post-mortem, blog post.
*Effort : faible (3-4h). Risque : aucun.*
A aussi a voir on tu l'integre dans le chat

## 🔗 Bonus — intégrations IDE

### 26. Consommer les diagnostics LSP/inspections IntelliJ ✅ DONE (2026-05-15)
Au start d'un turn, packer la liste des erreurs/warnings du buffer courant (via `HighlightInfo` IntelliJ) en system prompt extension : claude voit immédiatement "fix the 3 errors lines 12, 34, 78". Pas besoin de copier-coller.
*Effort : moyen (1j). Risque : explosion du prompt si beaucoup d'inspections → limiter aux errors only.*
Question comment il se declenche ?


## ⚡ Bonus — performance / coût


### 31. Cost cap & weekly budget ⏳ TO TEST (2026-05-16 — pending user validation)
Settings → budget mensuel / hebdomadaire en $. Soft warning à 80%, hard stop à 100% avec confirmation. Lecture des coûts via les events `total_cost_usd`. Histogramme par jour dans Tools → AgentNav → Usage.
*Effort : moyen (1-2j surtout UI). Risque : aucun.*

## 🧠 Bonus — features Claude Code non exposées

### 32. Sub-agents UI ⏳ TO TEST (2026-05-16 — pending user validation)
`system:init.agents` liste `[Explore, general-purpose, Plan, statusline-setup, custom user agents…]`. Dropdown dédié dans le toolbar pour invoquer un sub-agent (équivalent CLI `--agent <name>`). Le sub-agent prend un prompt isolé sans polluer la conv principale.
*Effort : moyen (1j). Risque : aucun.*
Tres bien ca mais faudras utiliser pas mal de raccourci avec / pour ne pas remplir l'ui du chat

### 35. Plugin marketplace Claude Code
`system:init.plugins` (toujours vide chez toi mais existe). Page Settings → AgentNav → Plugins listant ceux installés (`claude plugin list`), avec install/uninstall via UI.
*Effort : moyen (1j). Risque : aucun ; usage croit avec maturité de Claude Code.*
A faire en dernier

### 36. Output style switcher
`--output-style` permet de changer le ton (concise, verbose, structured…). Dropdown rapide si claude expose plusieurs styles via init.
*Effort : faible (2-3h). Risque : aucun.*
A quoi sa sert ce point la concreteemnt ?


### 37. Memory paths inspector ✅ DONE (2026-05-15)
`system:init.memory_paths` liste les fichiers de mémoire auto chargés (`~/.claude/projects/.../memory/MEMORY.md`). Action "View loaded memory" → table éditable + édit/delete des entries individuels. Évite d'aller fouiller le filesystem.
*Effort : moyen (1j). Risque : aucun, mais sensible (édite la mémoire de claude).*
A quoi sa sert ce point la concreteemnt ?

## 🤝 Bonus — collaboration / multi-user

### 39. Comments / annotations sur messages
Hover sur un message assistant → bouton "📝 annotate" → ajout d'une note locale (stockée en `.idea/agentnav-annotations.json`). Utile pour marquer "this was wrong, fix later" sans réécrire le prompt.
*Effort : faible (4-5h). Risque : aucun.*
Le annotate sera sur la view du code ou dans le chat ?

### 40. Voice input (whisper local)
Bouton 🎤 → enregistre audio → transcription via whisper.cpp local (binaire à installer) → texte inséré dans le textarea. Pour le dev qui veut dicter au lieu de taper.
*Effort : moyen (1-2j si whisper.cpp dispo localement, sinon élevé). Risque : qualité variable selon mic + bruit ambiant.*

## 🛠️ Bonus — DX interne (qualité code plugin)

### 43. Logger panel intégré ✅ DONE (2026-05-15)
Tool window onglet "Logs" qui montre les logs structurés du service (state changes, control_request/response, parse fails). Filtre par level/category. Évite d'aller dans idea.log.
*Effort : faible-moyen (1j). Risque : aucun, surtout utile pour support.*

## Priorité suggérée pour la prochaine session

À discuter, mais sur la base de "visible/utile/effort", j'attaquerais dans cet ordre :
1. **#3 (token/coût statusbar)** — quick win
2. **#5 (indicateur activité)** — quick win
3. **#9 (persistance tabs)** — frustration récurrente
4. **#1 (inline diff)** — gros gain UX
5. **#13 (Marketplace)** — pour partager


Point important Recheck la lliste des chats qui correspond au resume de claude pour bien le charger comme le resume ce que fait

## 🆕 Points ajoutés post-revue user

### 44. Resume picker — preview plus pertinent ✅ DONE (2026-05-15)
**État actuel** : la card du dialog Resume affiche le `first user message` brut. Sur la plupart des sessions, c'est un wrapper Claude Code (`<local-command-caveat>`, `<system-reminder>`, `<command-name>`) injecté avant le vrai prompt humain → la card devient illisible.
**Proposition** :
- Skipper les lignes qui commencent par `<local-command-caveat>`, `<system-reminder>`, `<command-name>`, `<task-notification>` pour trouver le **vrai** premier prompt user.
- Afficher dans la card à la fois `first` (avec sa date) et `last` (avec sa date) — l'user sait où il en était.
- Si un event `type: "summary"` est présent dans le .jsonl, l'utiliser en priorité comme titre de la card.
**Effort** : faible (3-4h, c'est juste parser smarter dans `ClaudeSessionsService.parseSession`).
**Risque** : aucun.

### 45. Toolbar input — slash commands à la place des dropdowns ✅ DONE (2026-05-15)
**État actuel** : 5 dropdowns dans le toolbar de l'input (Mode, Model, Effort, Skills, MCP). Visuellement chargé, prend toute la largeur.
**Proposition** :
- Garder uniquement **2 boutons icons compacts** : `+` (upload/attach), `▶/⏹` (send/stop). Boutons petits, centrés sur l'icon, sans label texte.
- Garder **1 dropdown** : le switch agent (Claude Code ↔ OpenCode).
- Tout le reste passe en **slash commands** dans le chat lui-même :
    - `/mode` → liste interactive des modes (acceptEdits, plan, default, bypassPermissions) → click = switch
    - `/model` → liste Opus/Sonnet/Haiku → click = switch
    - `/effort` → Low/Med/High/Max → click = switch
    - `/skill` → liste des skills user + built-in → click = invoque
    - `/mcp` → liste des servers/tools MCP → click = mention dans le prompt
- L'idée : zéro encombrement visuel, tout passe par le chat, l'IA peut elle-même proposer "tu veux switcher en plan mode ?" via cartes interactives.
**Effort** : moyen (1-2j surtout côté UX/parser des slash + cards interactives à designer).
**Risque** : changement d'habitude pour l'user → fournir tooltip "type / to see commands" sur le textarea + mention dans le first-run hint.

### 46. Skill Python : extract context from another .jsonl ✅ DONE (2026-05-15)
**État actuel** : pour réinjecter le contexte d'un ancien chat dans le chat courant, il faut copier-coller manuellement.
**Proposition** : créer un skill custom (script Python dans `~/.claude/skills/extract-context/`) que claude peut invoquer via `/extract-context`. Le skill :
- prend en argument un sid (ou path vers un .jsonl)
- parse le NDJSON en filtrant les wrappers system (cf #44)
- extrait juste les messages user + assistant texte (skip thinking, tool_use)
- formate en markdown compact : `## Prompt 1 (date)\nuser: …\nassistant: …\n## Prompt 2 …`
- retourne le markdown que claude lira comme contexte
- Bonus : option `--last N` pour ne prendre que les N derniers échanges.
**Effort** : faible (3-4h Python + manifest skill).
**Risque** : aucun. À distribuer dans le repo plugin pour install automatique au boot si l'user a opt-in.

### 47. Input panel — design redesign global ✅ DONE (2026-05-15)
**État actuel** : input prend beaucoup de hauteur, footer avec WrapLayout multi-row possible, boutons trop gros.
**Proposition** :
- Réduire l'`emptyBorder` du textArea à 4px verticaux au lieu de 8.
- Hauteur min textArea = 36px (une ligne) au lieu de 50px.
- Boutons icon-only en `JButton(Icon).apply { preferredSize = Dimension(28, 28); margin = empty }` au lieu des `margin = insets(4, 10)` actuels.
- Send/Stop : un seul bouton qui flip entre ▶ (12pt SF Symbol style) et ⏹, fond circulaire discret.
- Upload `+` : icon plus, hover montre tooltip "Attach file or image".
- Tout le reste passe en slash (cf #45).
- Footer hauteur cible : ~32px au lieu de 50+ aujourd'hui.
**Effort** : faible (4-6h, surtout du CSS-like swing tuning).
**Risque** : icônes compactes peuvent paraître peu découvrables → onboarding tooltip au 1er lancement.