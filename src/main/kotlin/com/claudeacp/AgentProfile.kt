package com.claudeacp

/**
 * Transport utilisé pour parler à l'agent.
 *  - ACP : protocole standard JSON-RPC ACP (initialize → session/new → session/prompt).
 *  - CLI_STREAM_JSON : on spawn directement le binaire `claude` avec `--input-format stream-json
 *    --output-format stream-json`. Pas de JSON-RPC, juste un flux d'events NDJSON
 *    self-contained. Utilise le plan d'abonnement interactif (pas l'API).
 */
enum class Transport { ACP, CLI_STREAM_JSON }

/**
 * Profil d'un agent (Claude Code, OpenCode, ou un agent custom).
 * Le transport détermine comment le plugin communique : ACP standard ou CLI stream-json direct.
 */
data class AgentProfile(
    val id: String,
    val displayName: String,
    /** Binaire ou commande (ex: "npx", "opencode", "/path/to/agent", "claude"). */
    val command: String,
    /** Arguments passés au binaire. */
    val args: List<String>,
    val transport: Transport = Transport.ACP,
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
            // CLI direct, pas l'ACP npm. Reste sur le plan d'abonnement interactif.
            command = "claude",
            args = listOf(
                "--output-format", "stream-json",
                "--input-format", "stream-json",
                "--verbose",
                "--permission-mode", "acceptEdits"
            ),
            transport = Transport.CLI_STREAM_JSON,
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
            transport = Transport.ACP,
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
