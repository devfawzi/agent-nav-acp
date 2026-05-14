package com.claudeacp

/**
 * Profil d'un agent ACP (Claude Code, OpenCode, ou un agent custom déclaré par l'utilisateur).
 *
 * Le plugin lance n'importe quel agent qui implémente le protocole ACP standard
 * (initialize → session/new → session/prompt → session/update). Les seules différences
 * entre agents sont la ligne de commande pour les lancer + leurs capabilities exposées.
 */
data class AgentProfile(
    val id: String,
    val displayName: String,
    /** Binaire ou commande (ex: "npx", "opencode", "/path/to/agent"). */
    val command: String,
    /** Arguments passés au binaire. */
    val args: List<String>,
    val docUrl: String = "",
    val installCommands: List<String> = emptyList(),
    val authInstructions: String = "",
    val isBuiltin: Boolean = false
) {
    fun fullCommandLine(): String = (listOf(command) + args).joinToString(" ")

    companion object {
        val CLAUDE_CODE = AgentProfile(
            id = "claude-code",
            displayName = "Claude Code",
            command = "npx",
            args = listOf("--yes", "@agentclientprotocol/claude-agent-acp"),
            docUrl = "https://docs.claude.com/en/docs/claude-code/quickstart",
            installCommands = listOf(
                "curl -fsSL https://claude.ai/install.sh | bash",
                "claude  # then complete the login flow"
            ),
            authInstructions = "Run `claude` once to sign in via your browser. Auth is stored in ~/.claude/.",
            isBuiltin = true
        )

        val OPENCODE = AgentProfile(
            id = "opencode",
            displayName = "OpenCode",
            command = "npx",
            args = listOf("-y", "opencode-ai", "acp"),
            docUrl = "https://opencode.ai/docs/acp/",
            installCommands = listOf(
                "npm install -g opencode-ai  # optional, npx works too",
                "npx opencode-ai auth login  # sign in (auth stored in ~/.opencode/)"
            ),
            authInstructions = "Run `npx opencode-ai auth login` (or `opencode auth login` if installed globally). " +
                "Auth is stored in ~/.opencode/ independent of how you launch it.",
            isBuiltin = true
        )

        val BUILTIN_PROFILES: List<AgentProfile> = listOf(CLAUDE_CODE, OPENCODE)
    }
}
