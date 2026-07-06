# AgentNav — IntelliJ Plugin

IntelliJ plugin that integrates **Claude Code** directly into the IDE (native CLI stream-json, stays on your subscription plan), plus **OpenCode and any ACP-compatible agent**. Claude Code is the default agent. Inspired by Cursor, with a UX tailored to JetBrains IDEs:

- 💬 Modern chat with Markdown rendering
- 🔄 Multi-session tabs (each chat = independent ACP `sessionId`)
- 📂 **Native diff viewer** auto-opened on every file change (use `>>` arrows to revert hunk-by-hunk)
- 📝 **Pending Changes** list with per-file or bulk Accept/Reject
- 🧠 Collapsible "Thinking" block for Claude's extended reasoning
- 🔧 Cards for `Edit`/`Write` operations with +/− line counts and inline actions
- ⏹ Stop running prompts or auto-interrupt when sending a new one
- 🎛 **Model / Mode / Effort** selectors directly in the prompt bar (Cursor-style)

---

## 📋 Prerequisites

| Component | Version | Why |
|---|---|---|
| **IntelliJ IDEA** | 2026.1.1+ (Community or Ultimate) | Target platform |
| **JDK 21** | OpenJDK / Temurin 21 LTS | Plugin build toolchain |
| **Node.js** | ≥ 20.x (LTS recommended) | To run `@agentclientprotocol/claude-agent-acp` via `npx` |
| **Claude Code CLI** | latest | Authentication + access to the Claude API |
| **Anthropic subscription** | Pro, Max, or API key | To run prompts |

> **No need to manually install `@agentclientprotocol/claude-agent-acp`** — the plugin downloads it on the fly via `npx --yes` on first launch.

---

## 🚀 Installing prerequisites

### 1. Node.js (via nvm — recommended)

```bash
# Linux / macOS
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/master/install.sh | bash
exec $SHELL                 # reload the shell
nvm install --lts           # install latest LTS
nvm use --lts
node -v && npx -v           # verify
```

**Alternatives**:
- macOS: `brew install node`
- Windows: [nodejs.org/en/download](https://nodejs.org/en/download/)
- Ubuntu/Debian: `sudo apt install nodejs npm`

### 2. Claude Code CLI

```bash
# Official installer (Linux / macOS / WSL)
curl -fsSL https://claude.ai/install.sh | bash

# Sign in (opens a browser)
claude

# Verify
claude --version
```

**Full docs**: [docs.claude.com/en/docs/claude-code/quickstart](https://docs.claude.com/en/docs/claude-code/quickstart)

### 3. JDK 21 (only to build the plugin)

```bash
# SDKMAN (recommended)
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 21.0.5-tem

# Or:
brew install --cask temurin@21          # macOS
sudo apt install openjdk-21-jdk         # Ubuntu/Debian
# Windows: https://adoptium.net/temurin/releases/?version=21
```

### 4. On Linux: raise the `inotify` limit (optional but recommended)

IntelliJ relies on `inotify` to detect file changes. When Claude modifies many files at once, the system limit may be hit (you'll see `ENOSPC` warnings).

```bash
echo 'fs.inotify.max_user_watches=1048576' | sudo tee /etc/sysctl.d/60-inotify.conf
sudo sysctl --system
```

---

## 📦 Installing the plugin

### Option A — From a local ZIP

```bash
git clone <this-repo>
cd agent-nav-acp

# Build (first time: 5–10 min, downloads the IntelliJ SDK ~1 GB)
export JAVA_HOME=/path/to/jdk-21
./gradlew buildPlugin
```

The ZIP is generated at `build/distributions/agent-nav-acp-<version>.zip`.

In IntelliJ:
1. **Settings → Plugins → ⚙ → Install Plugin from Disk…**
2. Pick the ZIP
3. **Restart IDE**

### Option B — Quick test (throwaway IntelliJ instance)

```bash
./gradlew runIde
```

Launches a temporary IntelliJ instance with the plugin loaded. Handy for development.

---

## 🤝 Multi-agent support

AgentNav works with **any ACP-compatible agent**. Two profiles are bundled out of the box:

| Agent | Default launch command | Auth location |
|---|---|---|
| **Claude Code** | `npx --yes @agentclientprotocol/claude-agent-acp` | `~/.claude/` |
| **OpenCode** ⭐ | `opencode acp` (local) or `npx -y opencode-ai acp` (fallback) | `~/.opencode/` |

### ⭐ Recommended: OpenCode

**OpenCode is the recommended agent** — open-source, supports `/connect` to link your
GO subscription, faster iteration, and full ACP feature parity.

```bash
# 1. Install OpenCode globally (the plugin will detect the local binary and use it
#    directly instead of slow npx)
npm install -g opencode-ai

# 2. Sign in
opencode auth login

# 3. (Optional but recommended) Connect to your GO subscription for unlimited usage:
#    in the OpenCode chat, type:
/connect
```

> ⚠️ **IMPORTANT — After installing OpenCode (or any agent)** :
> open the plugin's **Settings → Tools → AgentNav** and click **"Auto-detect now"**.
> This refreshes the cached binary path. Without this step the plugin may keep using
> the npx fallback (or fail to find the binary at all).
>
> Same step applies when you install Claude Code, or when you reinstall any agent in
> a different location (nvm switch, brew upgrade, etc.).

### Installing Claude Code

```bash
# Linux / macOS / WSL
curl -fsSL https://claude.ai/install.sh | bash

# Sign in (opens a browser)
claude

# After install: Settings → Tools → AgentNav → Auto-detect now
```

### Switching agents

Click the **Agent ▾** button in the prompt bar (next to the `+` attachments button) to
switch between agents on the fly. Switching:

- Stops the current ACP process
- Resets the active chat session (a new ACP session will be created when you send your
  next prompt — the agent doesn't know about the previous one)
- Starts the new agent

### Adding a custom agent

Any agent that implements the [Agent Client Protocol](https://agentclientprotocol.com/)
can be added manually:

1. **Settings → Tools → AgentNav**
2. **Add custom…** in the *Agents* section
3. Fill in:
   - **Display name** — shown in the dropdown
   - **Command** — binary or first word (e.g. `npx`, `opencode`, `/path/to/agent`)
   - **Args** — comma-separated (e.g. `-y,my-agent-acp` or `acp,--port,0`)
4. **Test connection** — launches the binary, sends `initialize`, and verifies a
   valid JSON-RPC response within 15 seconds
5. **Apply** — the custom agent appears in the dropdown alongside Claude Code and
   OpenCode

> The plugin doesn't install the agent for you — you're responsible for having the
> binary on your machine and authenticating it. The plugin only knows how to *launch*
> ACP-compatible processes.

See [agentclientprotocol.com](https://agentclientprotocol.com/) for the list of
ACP-compatible agents (Gemini, Codex, Qwen, Hermes, OpenClaw, etc.).

---

## 🔍 Automatic binary discovery

On startup, the plugin locates `claude` and `npx` **without any required configuration**, in this priority order:

1. **Environment variables** — for power users / CI:
   ```bash
   export CLAUDE_CLI_PATH=/custom/path/claude
   export NPX_PATH=/custom/path/npx
   ```
2. **IntelliJ Settings** — `Settings → Tools → AgentNav` (manual override with file picker)
3. **Common paths** — auto-discovery in: `~/.local/bin`, `~/.npm-global/bin`, `~/.nvm/versions/node/*/bin`, `/usr/local/bin`, `/opt/homebrew/bin`, `%APPDATA%\npm\` (Windows), JetBrains-bundled Node runtime, etc.
4. **`which` / `where`** — last resort

If nothing is found, the UI shows an **onboarding panel** with copyable install commands and an **Open Settings** button to configure paths manually.

---

## 🎮 Usage

1. **View → Tool Windows → AgentNav** (⚡ icon on the right)
2. The agent starts automatically (state shown in the header: `🚀 Starting...` → `✅ Ready`)
3. Type your prompt and press **Enter** (Shift+Enter for newline)
4. When Claude modifies a file:
   - A **card** appears in the chat with the file name, +/− line counts, and actions
   - The **📝 Pending Changes** bar fills up (collapsible)
   - The **diff viewer opens automatically** as an editor tab
   - You can **revert an individual hunk** via the native `>>` arrows in the IntelliJ diff
5. **Accept** or **Reject** per file, or in bulk via the buttons in the Pending Changes header
6. While Claude is responding, the `➤` button becomes `⏹` (stop). You can also start typing a new prompt to interrupt the current response and send the next one.
7. Click **+** in the tool window title bar to start a new chat (= new ACP session, fully isolated).

### Model / Mode / Effort selectors

Below the prompt area, three dropdowns are auto-populated from the capabilities returned by the agent:
- **Mode**: Default / Bypass Permissions / etc.
- **Model**: Default / Opus / Sonnet / Haiku…
- **Effort**: low / medium / high (thinking depth)

Selections are sent to the agent via `session/set_model`, `session/set_mode`, `session/set_config_option`.

---

## 🏗 Architecture

```
src/main/kotlin/com/claudeacp/
├── ClaudeACPService.kt              # Spawns ACP, parses JSON-RPC, manages multiple sessions
├── PromptHistoryService.kt          # Per-session prompt history
├── PendingChangesService.kt         # In-flight file changes (accept/reject)
├── DiffViewerManager.kt             # Editor-tab diff with auto-refresh on changes
├── AgentBinaryResolver.kt           # Binary auto-discovery (env → settings → which)
├── AgentSettings.kt                 # Persistent settings (path overrides)
├── AgentSettingsConfigurable.kt     # Settings page: Tools → AgentNav
├── ClaudeACPToolWindowFactory.kt    # Native IntelliJ multi-session tabs
├── ClaudeACPToolWindowPanel.kt      # Main tool window UI
├── ChatPanel.kt                     # Modern chat (bubbles, thinking, tools, run cmd)
├── PendingChangesPanel.kt           # Collapsible changes bar
├── PromptInputPanel.kt              # Input + Model/Mode/Effort selectors
└── OnboardingPanel.kt               # View shown when prerequisites are missing
```

---

## 🐛 Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| "Setup required" panel on launch | `claude` or `npx` not found | Follow the panel instructions, or set paths in `Settings → Tools → AgentNav` |
| Agent doesn't respond | Not logged into Claude Code | Run `claude` in a terminal to (re)sign in |
| Diff doesn't open automatically | Linux `inotify` limit reached | See *Raise inotify limit* above |
| "Method not found" | ACP version too old (cached) | `npx clear-npx-cache` then restart the IDE |
| Empty tabs at startup | Corrupted sandbox state (dev only) | `rm -rf .intellijPlatform/sandbox/*/IU-*/{config,system}` |

**Detailed logs**: `Help → Show Log in Files` — search for `com.claudeacp`.

---

## 🧪 Tech stack

- **Kotlin** 2.3.21
- **Gradle** 9.0
- **IntelliJ Platform Gradle Plugin** 2.15
- **Target build**: IntelliJ 2026.1.1 (`sinceBuild=261`)
- **JDK** 21 LTS
- **Dependencies**: `gson`, `kotlinx-coroutines`, `commonmark-java`

---

## 📚 References

- [Agent Client Protocol (ACP)](https://agentclientprotocol.com/)
- [`@agentclientprotocol/claude-agent-acp` (npm)](https://www.npmjs.com/package/@agentclientprotocol/claude-agent-acp)
- [Claude Code Documentation](https://docs.claude.com/en/docs/claude-code/quickstart)
- [IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/welcome.html)
- [IntelliJ Platform Gradle Plugin 2.x](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)

---

## 📄 License

MIT — see `LICENSE`.
