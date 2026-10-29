package com.lscardinali.pocketwow.realm

import android.content.Context
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64

/**
 * Runs worldserver console commands (e.g. `saveall`) over its SOAP endpoint on loopback.
 *
 * SOAP logs in with a GM account, so the app keeps one of its own, [ACCOUNT], with a random
 * password stored in app storage and re-set on every start. A raw socket is used because
 * Android's cleartext policy blocks plain HTTP through HttpURLConnection.
 */
class WorldConsole(context: Context) {

    private val secretFile = File(context.filesDir, "azerothcore/soap.secret")

    /** Blocking: (re)creates the service account. Needs the auth database up. */
    fun prepare(accounts: Accounts) {
        val password = secretFile.takeIf { it.isFile }?.readText()?.trim()
            ?: randomPassword().also {
                secretFile.parentFile?.mkdirs()
                secretFile.writeText(it)
            }
        accounts.ensureGm(ACCOUNT, password)
    }

    /** Blocking: runs [command] and returns its output, or throws on a SOAP fault or I/O error. */
    fun execute(command: String, timeoutMs: Int = 10_000): String {
        val password = secretFile.readText().trim()
        val body = """<?xml version="1.0" encoding="utf-8"?>
            |<SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ns1="urn:AC">
            |<SOAP-ENV:Body><ns1:executeCommand><command>${escape(command)}</command></ns1:executeCommand></SOAP-ENV:Body>
            |</SOAP-ENV:Envelope>""".trimMargin().toByteArray()
        val auth = Base64.getEncoder().encodeToString("$ACCOUNT:$password".toByteArray())
        val reply = Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", WorldServer.SOAP_PORT), timeoutMs)
            socket.soTimeout = timeoutMs
            val head = "POST / HTTP/1.0\r\nHost: 127.0.0.1\r\nContent-Type: text/xml\r\n" +
                "Authorization: Basic $auth\r\nContent-Length: ${body.size}\r\n\r\n"
            socket.getOutputStream().apply { write(head.toByteArray() + body); flush() }
            socket.getInputStream().readBytes().decode()
        }
        RESULT.find(reply)?.let { return unescape(it.groupValues[1]) }
        throw IllegalStateException(FAULT.find(reply)?.groupValues?.get(1)?.let(::unescape) ?: reply.lineSequence().first())
    }

    companion object {
        /** Hidden from the app's account list. */
        const val ACCOUNT = "POCKETWOW"
        private val RESULT = Regex("<result>(.*?)</result>", RegexOption.DOT_MATCHES_ALL)
        private val FAULT = Regex("<faultstring>(.*?)</faultstring>", RegexOption.DOT_MATCHES_ALL)
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        private fun randomPassword(): String {
            val random = SecureRandom()
            return (1..16).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        }

        private fun ByteArray.decode() = toString(Charsets.UTF_8)

        private fun escape(text: String) =
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        private fun unescape(text: String) = text.replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&#xD;", "\r").replace("&amp;", "&")
    }
}
