package com.agentnav.claude

import com.agentnav.core.PromptAttachment
import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Golden tests des messages émis sur stdin de claude. Toute divergence = changement de
 * schéma wire → à valider contre le CLI réel avant de merger.
 */
class ClaudeRequestsTest {

    @Test
    fun `userMessage simple`() {
        assertEquals(
            """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"hello"}]}}""",
            ClaudeRequests.userMessage("hello")
        )
    }

    @Test
    fun `userMessage avec image et file link`() {
        val json = ClaudeRequests.userMessage(
            "look",
            listOf(
                PromptAttachment.Image(displayName = "shot.png", mimeType = "image/png", base64Data = "QUJD"),
                PromptAttachment.FileLink(absolutePath = "/tmp/a.kt", isDirectory = false, displayName = "a.kt")
            )
        )
        assertEquals(
            """{"type":"user","message":{"role":"user","content":[""" +
                """{"type":"text","text":"look"},""" +
                """{"type":"image","source":{"type":"base64","media_type":"image/png","data":"QUJD"}},""" +
                """{"type":"text","text":" @/tmp/a.kt"}]}}""",
            json
        )
    }

    @Test
    fun `toolResult`() {
        assertEquals(
            """{"type":"user","message":{"role":"user","content":[""" +
                """{"type":"tool_result","tool_use_id":"tu_1","content":"approved"}]}}""",
            ClaudeRequests.toolResult("tu_1", "approved")
        )
    }

    @Test
    fun `interrupt`() {
        assertEquals(
            """{"type":"control_request","request_id":"int-1","request":{"subtype":"interrupt"}}""",
            ClaudeRequests.interrupt("int-1")
        )
    }

    @Test
    fun `setModel et setPermissionMode`() {
        assertEquals(
            """{"type":"control_request","request_id":"m-1","request":{"subtype":"set_model","model":"claude-sonnet-4-6"}}""",
            ClaudeRequests.setModel("m-1", "claude-sonnet-4-6")
        )
        assertEquals(
            """{"type":"control_request","request_id":"p-1","request":{"subtype":"set_permission_mode","mode":"plan"}}""",
            ClaudeRequests.setPermissionMode("p-1", "plan")
        )
    }

    @Test
    fun `permissionAllow renvoie updatedInput obligatoire`() {
        val input = JsonParser.parseString("""{"command":"ls"}""")
        assertEquals(
            """{"type":"control_response","response":{"subtype":"success","request_id":"perm_1",""" +
                """"response":{"behavior":"allow","updatedInput":{"command":"ls"}}}}""",
            ClaudeRequests.permissionAllow("perm_1", input)
        )
    }

    @Test
    fun `permissionAllow always inclut updatedPermissions`() {
        val input = JsonParser.parseString("""{"command":"ls"}""")
        val suggestions = """[{"type":"addRules","behavior":"allow"}]"""
        assertEquals(
            """{"type":"control_response","response":{"subtype":"success","request_id":"perm_2",""" +
                """"response":{"behavior":"allow","updatedInput":{"command":"ls"},""" +
                """"updatedPermissions":[{"type":"addRules","behavior":"allow"}]}}}""",
            ClaudeRequests.permissionAllow("perm_2", input, suggestions)
        )
    }

    @Test
    fun `permissionAllow sans input renvoie objet vide`() {
        assertEquals(
            """{"type":"control_response","response":{"subtype":"success","request_id":"perm_3",""" +
                """"response":{"behavior":"allow","updatedInput":{}}}}""",
            ClaudeRequests.permissionAllow("perm_3", null)
        )
    }

    @Test
    fun `permissionDeny`() {
        assertEquals(
            """{"type":"control_response","response":{"subtype":"success","request_id":"perm_4",""" +
                """"response":{"behavior":"deny","message":"nope"}}}""",
            ClaudeRequests.permissionDeny("perm_4", "nope")
        )
    }

    @Test
    fun `allowOnceDecision pour control_request inconnu`() {
        assertEquals(
            """{"type":"control_response","request_id":"x-1","response":{"decision":"allow_once"}}""",
            ClaudeRequests.allowOnceDecision("x-1")
        )
    }
}
