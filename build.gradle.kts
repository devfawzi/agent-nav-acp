import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.15.0"
}

group = "com.agentnav"
version = "0.9.4"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // La sandbox runIde MODIFIE sa distribution au boot (création de jbr/lib/extensions).
        // Si cette distribution vient du cache transforms gradle (immutable), chaque runIde le
        // corrompt → "The contents of the immutable workspace have been modified" à tout sync
        // suivant. Fix : utiliser une installation IDE locale (localIdePath dans
        // gradle.properties) ; fallback téléchargement pour la CI.
        val localIde = providers.gradleProperty("localIdePath").orNull
            ?.takeIf { it.isNotBlank() && file(it).exists() }
        if (localIde != null) local(localIde) else intellijIdea("2026.1.1")
    }
    
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.commonmark:commonmark:0.24.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

tasks.named<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>("runIde") {
    // La sandbox 2026.1 crashe sous Wayland natif (NPE WLWindowPeer.reactivate au
    // WelcomeScreen). Force XToolkit (XWayland) le temps que JetBrains fixe WLToolkit.
    jvmArgs("-Dawt.toolkit.name=XToolkit")
}

intellijPlatform {
    // Lance un IDE headless depuis la distribution — en conflit avec l'IDE local en cours
    // d'exécution (localIdePath) et inutile pour nos settings. Désactivé (pattern courant).
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
        }
        changeNotes = """
            <h3>0.9.3</h3>
            <ul>
                <li><b>Resume in current tab</b> with full history replay — past user/assistant
                    exchanges from the session .jsonl are re-rendered in the chat (system
                    wrappers, thinking and tool noise filtered out).</li>
                <li><b>Bidirectional rename</b> — renaming a chat tab (or the auto-title from
                    the first prompt) renames the claude session itself, visible in CLI
                    <code>/resume</code>.</li>
                <li>Resumed tab titles use claude's generated session summary when available.</li>
            </ul>
            <h3>0.9.2</h3>
            <ul>
                <li>Claude backend now runs entirely on the fixture-tested protocol parser —
                    no more inline JSON handling (−400 lines), every event path covered by tests.</li>
                <li><code>/context</code>, <code>/usage</code> and other builtin outputs render
                    as distinct "System output" blocks (they are not model replies).</li>
            </ul>
            <h3>0.9.1</h3>
            <ul>
                <li>Stream-json protocol now validated against real claude 2.1.201 captures
                    (16 fixtures, 43 tests) — replayable capture harness for future CLI upgrades.</li>
                <li>Discovered & wired <code>rename_session</code> control request (persisted
                    in the session .jsonl, visible in CLI <code>/resume</code>).</li>
                <li>Fixed silent exception on local-command outputs (e.g. after set_model).</li>
            </ul>
            <h3>0.9.0</h3>
            <ul>
                <li><b>AgentNav rework</b> — new plugin id <code>com.agentnav</code>, restructured
                    architecture: one isolated agent process per chat tab (Claude Code CLI
                    stream-json by default, OpenCode/ACP agents via a dedicated hub).</li>
                <li>ACP permission prompts now show Allow/Deny cards (no more silent auto-accept).</li>
                <li>TUI parity for slash commands: <code>/</code> popup populated from the first
                    keystroke (persistent caches + builtins), <code>/mcp</code> lists servers
                    instantly, <code>/config</code> opens the Settings panel.</li>
                <li>Fixed memory paths inspector, gradle transforms cache corruption (local IDE
                    for runIde), Wayland sandbox crash.</li>
            </ul>
            <h3>0.1.0</h3>
            <ul>
                <li>Session resume from <code>~/.claude/projects/</code> with searchable picker
                    and "current project only" filter.</li>
                <li>Permission prompts via <code>--permission-prompt-tool stdio</code> — inline
                    Allow/Deny cards for Bash and MCP tool requests.</li>
                <li>MCP servers and tools surfaced in a dedicated menu with status icons
                    (connected / needs-auth / failed).</li>
                <li>Skills and slash-commands menu, grouped by type (custom skills vs built-ins).</li>
                <li>Stop button is now interrupt-only (never sends pending text), works during
                    tool loops, and falls back to process kill if the interrupt write fails.</li>
                <li>Model / permission-mode switching via <code>control_request</code>, with
                    rollback if Claude rejects the change.</li>
                <li>Effort/Bypass switches now resume the conversation across process respawn
                    via <code>--resume</code> — no more lost history when changing extended
                    thinking level.</li>
                <li>PATH is auto-enriched at spawn (<code>~/.nvm/.../bin</code>, <code>~/.local/bin</code>,
                    <code>~/.cargo/bin</code>, <code>/opt/homebrew/bin</code>) so MCP servers can
                    find <code>npx</code>, <code>uvx</code>, etc. even when IntelliJ is launched
                    from a GUI without a login shell.</li>
                <li>Plan mode write/edit preview cards.</li>
                <li>Tool call cards now show file path / command / pattern inline (Read, Edit,
                    Write, Bash, Grep, WebFetch, Skill, mcp__*) and are expanded by default.</li>
                <li>Stays on the interactive subscription plan (no <code>-p</code>, no Agent SDK
                    credit billing — confirmed working with claude 2.1.123).</li>
            </ul>
        """.trimIndent()
    }
}