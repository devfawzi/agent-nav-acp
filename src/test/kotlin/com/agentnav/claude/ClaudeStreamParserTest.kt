package com.agentnav.claude

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.DynamicTest
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Rejoue les fixtures NDJSON capturées sur le vrai claude (tools/capture_fixtures.py)
 * contre ClaudeStreamParser. Deux niveaux :
 *  1. Générique — AUCUNE ligne d'AUCUNE fixture ne produit ParseError ni Unknown :
 *     un event inconnu = drift de protocole à prendre en charge explicitement.
 *  2. Ciblé — chaque scénario contient les événements clés attendus.
 */
class ClaudeStreamParserTest {

    private val fixturesRoot = File("src/test/resources/fixtures/claude")

    private fun fixtureDirs(): List<File> =
        fixturesRoot.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()

    private fun parseFixture(dir: File, name: String): List<ClaudeEvent> {
        val file = File(dir, "$name.ndjson")
        assertTrue(file.isFile, "fixture manquante: ${file.path} — relancer tools/capture_fixtures.py")
        return file.readLines().flatMap { ClaudeStreamParser.parse(it) }
    }

    // ─── 1. Générique : tout le corpus se parse sans trou ────────────────────

    @TestFactory
    fun `aucune fixture ne produit ParseError ni Unknown`(): List<DynamicTest> =
        fixtureDirs().flatMap { dir ->
            (dir.listFiles { f -> f.extension == "ndjson" } ?: emptyArray()).map { file ->
                DynamicTest.dynamicTest("${dir.name}/${file.name}") {
                    val events = file.readLines().flatMap { ClaudeStreamParser.parse(it) }
                    val errors = events.filterIsInstance<ClaudeEvent.ParseError>()
                    val unknown = events.filterIsInstance<ClaudeEvent.Unknown>()
                    if (errors.isNotEmpty()) {
                        fail("ParseError dans ${file.name}: ${errors.first().message} — raw=${errors.first().raw.take(150)}")
                    }
                    if (unknown.isNotEmpty()) {
                        fail("Event inconnu dans ${file.name}: types=${unknown.map { it.type }.distinct()} — à typer dans ClaudeEvent")
                    }
                }
            }
        }

    // ─── 2. Assertions ciblées par scénario (sur chaque version capturée) ────

    private fun forEachVersion(scenario: String, block: (List<ClaudeEvent>) -> Unit) {
        val dirs = fixtureDirs()
        assertTrue(dirs.isNotEmpty(), "aucune fixture — lancer tools/capture_fixtures.py")
        dirs.forEach { dir -> block(parseFixture(dir, scenario)) }
    }

    @Test
    fun `simple-text — init, texte assistant non synthétique, result sans erreur`() =
        forEachVersion("simple-text") { events ->
            assertTrue(events.any { it is ClaudeEvent.Init }, "Init attendu")
            assertTrue(events.any { it is ClaudeEvent.AssistantText && !it.isSynthetic && it.text.contains("pong") },
                "AssistantText(pong) attendu")
            assertTrue(events.any { it is ClaudeEvent.TurnResult && !it.isError }, "TurnResult ok attendu")
        }

    @Test
    fun `simple-text — hooks SessionStart typés`() =
        forEachVersion("simple-text") { events ->
            // L'user a des hooks SessionStart configurés : ils doivent être typés Hook, pas Unknown.
            assertTrue(events.filterIsInstance<ClaudeEvent.Hook>().isNotEmpty()
                || events.filterIsInstance<ClaudeEvent.SystemOther>().isEmpty(),
                "hooks attendus typés (Hook) quand présents")
        }

    @Test
    fun `thinking — au moins un bloc thinking`() =
        forEachVersion("thinking") { events ->
            assertTrue(events.any { it is ClaudeEvent.AssistantThinking }, "AssistantThinking attendu")
        }

    @Test
    fun `write-file — ToolUse Write puis ToolResult sans erreur`() =
        forEachVersion("write-file") { events ->
            assertTrue(events.any { it is ClaudeEvent.ToolUse && it.name == "Write" }, "ToolUse(Write) attendu")
            assertTrue(events.any { it is ClaudeEvent.ToolResult && !it.isError }, "ToolResult ok attendu")
        }

    @Test
    fun `edit-file — ToolUse Edit avec old_string et new_string mappés`() =
        forEachVersion("edit-file") { events ->
            val edit = events.filterIsInstance<ClaudeEvent.ToolUse>().firstOrNull { it.name == "Edit" }
                ?: fail("ToolUse(Edit) attendu")
            val info = ToolCallMapper.fromToolUse(edit, "sid", "acceptEdits")
            assertTrue(info.editOldString != null && info.editNewString != null,
                "editOldString/editNewString attendus (plan preview en dépend)")
        }

    @Test
    fun `bash-allow — CanUseTool format 2_1 (non legacy) pour Bash`() =
        forEachVersion("bash-allow") { events ->
            val perm = events.filterIsInstance<ClaudeEvent.CanUseTool>().firstOrNull()
                ?: fail("CanUseTool attendu")
            assertTrue(perm.toolName == "Bash", "toolName=Bash attendu, reçu ${perm.toolName}")
            assertTrue(!perm.legacy, "format claude 2.1+ attendu (non legacy)")
        }

    @Test
    fun `bash-deny — le deny produit un turn qui se termine`() =
        forEachVersion("bash-deny") { events ->
            assertTrue(events.any { it is ClaudeEvent.CanUseTool }, "CanUseTool attendu")
            assertTrue(events.any { it is ClaudeEvent.TurnResult }, "TurnResult attendu après deny")
        }

    @Test
    fun `plan-mode — init en mode plan`() =
        forEachVersion("plan-mode") { events ->
            val init = events.filterIsInstance<ClaudeEvent.Init>().firstOrNull() ?: fail("Init attendu")
            assertTrue(init.permissionMode == "plan", "permissionMode=plan attendu, reçu ${init.permissionMode}")
        }

    @Test
    fun `interrupt — le turn interrompu émet un result`() =
        forEachVersion("interrupt") { events ->
            assertTrue(events.any { it is ClaudeEvent.TurnResult }, "TurnResult attendu après interrupt")
        }

    @Test
    fun `set-model — control_response avec requestId corrélable`() =
        forEachVersion("set-model") { events ->
            val resp = events.filterIsInstance<ClaudeEvent.ControlResponse>().lastOrNull()
                ?: fail("ControlResponse attendu")
            assertTrue(resp.requestId?.startsWith("fix-set_model") == true,
                "requestId du harness attendu, reçu ${resp.requestId}")
        }

    @Test
    fun `set-permission-mode — status ou init reflète le nouveau mode`() =
        forEachVersion("set-permission-mode") { events ->
            val ok = events.any { it is ClaudeEvent.Status && it.permissionMode == "plan" } ||
                events.filterIsInstance<ClaudeEvent.Init>().lastOrNull()?.permissionMode == "plan" ||
                events.filterIsInstance<ClaudeEvent.ControlResponse>().any { it.success }
            assertTrue(ok, "confirmation du switch plan attendue (status/init/control_response)")
        }

    @Test
    fun `tool-error — ToolResult isError avec message`() =
        forEachVersion("tool-error") { events ->
            val err = events.filterIsInstance<ClaudeEvent.ToolResult>().firstOrNull { it.isError }
                ?: fail("ToolResult(isError) attendu")
            assertTrue(!err.errorText.isNullOrBlank(), "errorText attendu")
        }

    @Test
    fun `slash-context — réponse synthétique détectée`() =
        forEachVersion("slash-context") { events ->
            assertTrue(events.any { it is ClaudeEvent.AssistantText && it.isSynthetic },
                "AssistantText(isSynthetic=true) attendu pour /context")
        }

    @Test
    fun `slash-usage — réponse synthétique coût zéro`() =
        forEachVersion("slash-usage") { events ->
            assertTrue(events.any { it is ClaudeEvent.AssistantText && it.isSynthetic }, "synthétique attendu")
            val result = events.filterIsInstance<ClaudeEvent.TurnResult>().firstOrNull() ?: fail("TurnResult attendu")
            assertTrue(result.totalCostUsd == 0.0, "coût 0 attendu pour une slash builtin")
        }

    @Test
    fun `slash-unknown — message d'erreur synthétique, pas de crash`() =
        forEachVersion("slash-unknown") { events ->
            assertTrue(events.any { it is ClaudeEvent.AssistantText }, "réponse texte attendue")
        }

    @Test
    fun `resume — la session résumée retrouve le contexte`() =
        forEachVersion("resume") { events ->
            assertTrue(events.any { it is ClaudeEvent.AssistantText && it.text.contains("zebra42") },
                "le codeword de la session d'origine doit revenir après --resume")
        }
}
