package com.claudeacp

import java.io.File

/**
 * Localise les binaires `claude` (Claude Code CLI) et `npx` selon la stratégie suivante :
 *
 * 1. Variable d'environnement explicite (`CLAUDE_CLI_PATH`, `NPX_PATH`)
 * 2. Settings persistants utilisateur (via [AgentSettings])
 * 3. Chemins standards courants (npm global, nvm, homebrew, etc.)
 * 4. Commande shell `which`/`where` en fallback ultime
 *
 * Renvoie `null` si aucune méthode ne trouve un binaire exécutable.
 */
object AgentBinaryResolver {

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    fun resolveClaudeCli(): String? = resolve(
        envVar = "CLAUDE_CLI_PATH",
        settingsGetter = { AgentSettings.getInstance().getClaudeCliPathOrNull() },
        candidates = claudeCliCandidates(),
        binaryName = if (isWindows) "claude.cmd" else "claude"
    )

    fun resolveNpx(): String? = resolve(
        envVar = "NPX_PATH",
        settingsGetter = { AgentSettings.getInstance().getNpxPathOrNull() },
        candidates = npxCandidates(),
        binaryName = if (isWindows) "npx.cmd" else "npx"
    )

    /** Cherche un binaire `opencode` installé localement (npm install -g, curl install, etc.). */
    fun resolveOpencode(): String? = resolve(
        envVar = "OPENCODE_PATH",
        settingsGetter = { AgentSettings.getInstance().getOpencodePathOrNull() },
        candidates = opencodeCandidates(),
        binaryName = if (isWindows) "opencode.cmd" else "opencode"
    )

    private fun opencodeCandidates(): List<String> {
        val home = System.getProperty("user.home")
        val result = mutableListOf(
            "$home/.opencode/bin/opencode",
            "$home/.npm-global/bin/opencode",
            "$home/.local/bin/opencode",
            "/usr/local/bin/opencode",
            "/opt/homebrew/bin/opencode",
            "/usr/bin/opencode"
        )
        // nvm
        val nvmDir = File("$home/.nvm/versions/node")
        if (nvmDir.isDirectory) {
            nvmDir.listFiles()
                ?.sortedByDescending { it.name }
                ?.forEach { result.add("${it.absolutePath}/bin/opencode") }
        }
        // Windows
        if (isWindows) {
            System.getenv("APPDATA")?.let { result.add("$it\\npm\\opencode.cmd") }
        }
        return result
    }

    /** Cherche une command dans le PATH (utilisée pour les custom profiles avec command non-absolue). */
    fun resolveCommandInPath(name: String): String? {
        // Si c'est un chemin absolu existant
        val asFile = File(name)
        if (asFile.isAbsolute && asFile.canExecute()) return asFile.absolutePath
        return runWhich(name)
    }

    /**
     * Résout la command line effective pour un profil donné.
     * Pour OpenCode : tente le binaire local d'abord (rapide + même auth dans ~/.opencode/),
     * sinon fallback sur npx.
     */
    fun resolveProfileCommand(profile: AgentProfile): Pair<String, List<String>> {
        return when (profile.id) {
            "opencode" -> {
                val local = resolveOpencode()
                if (local != null) local to listOf("acp")
                else profile.command to profile.args
            }
            else -> profile.command to profile.args
        }
    }

    private fun resolve(
        envVar: String,
        settingsGetter: () -> String?,
        candidates: List<String>,
        binaryName: String
    ): String? {
        // 1. Env var explicite
        System.getenv(envVar)?.takeIf { it.isNotBlank() }?.let { path ->
            if (File(path).canExecute()) return path
        }

        // 2. Settings utilisateur
        settingsGetter()?.let { saved ->
            if (File(saved).canExecute()) return saved
        }

        // 3. Candidats hardcodés
        candidates.firstOrNull { File(it).canExecute() }?.let { return it }

        // 4. which/where
        return runWhich(binaryName)
    }

    private fun claudeCliCandidates(): List<String> {
        val home = System.getProperty("user.home")
        val result = mutableListOf(
            "$home/.local/bin/claude",
            "$home/.claude/local/claude",
            "$home/.npm-global/bin/claude",
            "/usr/local/bin/claude",
            "/opt/homebrew/bin/claude",
            "/usr/bin/claude"
        )
        // nvm : tester toutes les versions de node installées
        val nvmDir = File("$home/.nvm/versions/node")
        if (nvmDir.isDirectory) {
            nvmDir.listFiles()
                ?.sortedByDescending { it.name }
                ?.forEach { result.add("${it.absolutePath}/bin/claude") }
        }
        // npm prefix custom
        System.getenv("npm_config_prefix")?.let { result.add("$it/bin/claude") }
        // Windows
        if (isWindows) {
            System.getenv("APPDATA")?.let { result.add("$it\\npm\\claude.cmd") }
        }
        return result
    }

    private fun npxCandidates(): List<String> {
        val home = System.getProperty("user.home")
        val result = mutableListOf(
            "/usr/local/bin/npx",
            "/opt/homebrew/bin/npx",
            "/usr/bin/npx"
        )
        val nvmDir = File("$home/.nvm/versions/node")
        if (nvmDir.isDirectory) {
            nvmDir.listFiles()
                ?.sortedByDescending { it.name }
                ?.forEach { result.add("${it.absolutePath}/bin/npx") }
        }
        // Fallback : runtime Node embarqué par JetBrains (utilisé par AI Assistant officiel)
        result.add("$home/.cache/JetBrains/IntelliJIdea2026.1/acp-agents/.runtimes/node/24.13.0/bin/npx")
        // Windows
        if (isWindows) {
            System.getenv("APPDATA")?.let { result.add("$it\\npm\\npx.cmd") }
            System.getenv("ProgramFiles")?.let { result.add("$it\\nodejs\\npx.cmd") }
        }
        return result
    }

    private fun runWhich(name: String): String? = runCatching {
        val cmd = if (isWindows) listOf("where", name)
        else listOf("/bin/bash", "-lc", "which $name")
        val proc = ProcessBuilder(cmd).redirectErrorStream(false).start()
        proc.waitFor()
        proc.inputStream.bufferedReader().readLine()?.trim()
            ?.takeIf { it.isNotEmpty() && File(it).canExecute() }
    }.getOrNull()
}
