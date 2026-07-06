package com.agentnav.services

import com.agentnav.core.*
import com.agentnav.claude.*
import com.agentnav.acp.*
import com.agentnav.ui.*
import com.agentnav.services.*
import com.agentnav.settings.*

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * Teste qu'un profil ACP est valide : lance le binaire, envoie `initialize` et vérifie
 * qu'on reçoit une response JSON-RPC valide dans un délai raisonnable.
 *
 * Pas de session/new ni de prompt — juste le handshake pour confirmer que la command line
 * démarre un agent ACP-compatible.
 */
object AgentConnectionTester {

    data class Result(val success: Boolean, val detail: String)

    fun testProfile(profile: AgentProfile, timeoutMs: Long = 15_000): Result {
        return try {
            val (cmd, args) = AgentBinaryResolver.resolveProfileCommand(profile)
            val exePath = if (File(cmd).isAbsolute) cmd
            else if (cmd == "npx") AgentBinaryResolver.resolveNpx() ?: return Result(false, "npx not found in PATH")
            else AgentBinaryResolver.resolveCommandInPath(cmd) ?: return Result(false, "Command '$cmd' not found in PATH")

            val pb = ProcessBuilder(listOf(exePath) + args)
            pb.redirectErrorStream(false)
            val process = pb.start()

            // Envoie l'initialize
            val writer = OutputStreamWriter(process.outputStream, Charsets.UTF_8)
            val initMsg = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":""" +
                """{"protocolVersion":1,"clientCapabilities":""" +
                """{"fs":{"readTextFile":true,"writeTextFile":true}},""" +
                """"clientInfo":{"name":"AgentNav-Test","version":"0.1"}}}"""
            writer.write(initMsg)
            writer.write("\n")
            writer.flush()

            // Lit la première ligne de response avec timeout
            val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            val responseLine = StringBuilder()
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < timeoutMs) {
                if (reader.ready()) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("{")) {
                        responseLine.append(line)
                        break
                    }
                } else {
                    Thread.sleep(50)
                }
                if (!process.isAlive) break
            }

            // Cleanup
            try { process.destroyForcibly() } catch (_: Exception) {}
            try { process.waitFor(2, TimeUnit.SECONDS) } catch (_: Exception) {}

            if (responseLine.isEmpty()) {
                val err = process.errorStream.bufferedReader().readText().take(500)
                return Result(false, "No response from agent within ${timeoutMs}ms.\nStderr: $err")
            }
            // On a une response JSON : check que c'est un response (id=1) ou au moins du JSON-RPC
            val text = responseLine.toString()
            if (text.contains("\"jsonrpc\"") || text.contains("\"result\"")) {
                Result(true, "Agent responded to `initialize`:\n${text.take(300)}")
            } else {
                Result(false, "Got non-JSON-RPC output:\n${text.take(300)}")
            }
        } catch (e: Exception) {
            Result(false, "Exception: ${e.message}")
        }
    }
}
