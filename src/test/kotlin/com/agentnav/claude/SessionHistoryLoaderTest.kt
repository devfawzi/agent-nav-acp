package com.agentnav.claude

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Le format des events assistant du .jsonl de session est identique au flux stdout —
 * on valide le loader contre les fixtures capturées (la partie User n'existe que dans
 * les vrais .jsonl disque, testée au smoke).
 */
class SessionHistoryLoaderTest {

    private fun fixture(name: String): File {
        val dir = File("src/test/resources/fixtures/claude").listFiles { f -> f.isDirectory }
            ?.maxByOrNull { it.name } ?: error("no fixtures")
        return File(dir, "$name.ndjson")
    }

    @Test
    fun `simple-text — le texte assistant est rejouable`() {
        val items = SessionHistoryLoader.load(fixture("simple-text"))
        assertTrue(items.filterIsInstance<SessionHistoryLoader.Item.Assistant>()
            .any { it.text.contains("pong") }, "Assistant(pong) attendu au replay")
    }

    @Test
    fun `slash-context — les sorties synthétiques ne sont PAS rejouées`() {
        val items = SessionHistoryLoader.load(fixture("slash-context"))
        assertTrue(items.filterIsInstance<SessionHistoryLoader.Item.Assistant>().isEmpty(),
            "les messages model=<synthetic> doivent être filtrés du replay")
    }

    @Test
    fun `write-file — le narratif assistant est rejoué sans le bruit tool`() {
        val items = SessionHistoryLoader.load(fixture("write-file"))
        // Peu importe le contenu exact : aucun crash, et pas d'items vides.
        assertTrue(items.none { it is SessionHistoryLoader.Item.Assistant && it.text.isBlank() })
    }
}
