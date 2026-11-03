package com.lscardinali.azerothdroid.realm

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Game accounts, written straight into acore_auth the way AzerothCore's AccountMgr does
 * (SOAP would need an existing GM account to log in with).
 *
 * Passwords are never stored: only the SRP6 salt and verifier, computed as in
 * SRP6::CalculateVerifier. AzerothCore's big numbers are little-endian byte arrays.
 */
class Accounts(private val database: MariaDbServer) {

    /** Creates [name] (with GM level 3 when [gm]) and returns its account id. */
    fun create(name: String, password: String, gm: Boolean): Int = write(name, password, gm, replace = false)

    /** Creates [name] as a GM, or resets its password if it exists. For the app's own accounts. */
    fun ensureGm(name: String, password: String): Int = write(name, password, gm = true, replace = true)

    private fun write(name: String, password: String, gm: Boolean, replace: Boolean): Int {
        val username = name.uppercase()
        val pass = password.uppercase()
        require(NAME.matches(username)) { "Name: 1-16 letters or digits" }
        require(pass.length in 1..16 && pass.all { it.code in 0x21..0x7e }) {
            "Password: 1-16 characters, no spaces"
        }
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = verifier(username, pass, salt)
        val onDuplicate = if (replace) " ON DUPLICATE KEY UPDATE salt = VALUES(salt), verifier = VALUES(verifier)" else ""
        val output = database.execute(
            """
            INSERT INTO acore_auth.account (username, salt, verifier, expansion, reg_mail, email, joindate)
                VALUES ('$username', 0x${salt.hex()}, 0x${verifier.hex()}, 2, '', '', NOW())$onDuplicate;
            SET @id = (SELECT id FROM acore_auth.account WHERE username = '$username');
            ${if (gm) "INSERT IGNORE INTO acore_auth.account_access (id, gmlevel, RealmID) VALUES (@id, 3, -1);" else ""}
            SELECT @id;
            """.trimIndent(),
        )
        return output.lines().last { it.isNotBlank() }.trim().toInt()
    }

    fun list(): List<String> = database
        .execute("SELECT username FROM acore_auth.account ORDER BY id;")
        .lines().drop(1)
        .filter { it.isNotBlank() && !it.startsWith(BOT_PREFIX, ignoreCase = true) && it != WorldConsole.ACCOUNT }

    companion object {
        private val NAME = Regex("[A-Z0-9]{1,16}")
        /** mod-playerbots' Playerbots.RandomBotAccountPrefix. */
        private const val BOT_PREFIX = "rndbot"
        private val N = BigInteger("894B645E89E1535BBDAD5B8B290650530801B18EBFBF5E8FAB3C82872A3E9BB7", 16)
        private val G = BigInteger.valueOf(7)

        /** v = g ^ H(s || H(USER ":" PASS)) mod N, all little-endian. */
        fun verifier(username: String, password: String, salt: ByteArray): ByteArray {
            val inner = sha1("$username:$password".toByteArray())
            val x = littleEndian(sha1(salt + inner))
            return toLittleEndian(G.modPow(x, N), 32)
        }

        private fun sha1(data: ByteArray) = MessageDigest.getInstance("SHA-1").digest(data)

        private fun littleEndian(bytes: ByteArray) = BigInteger(1, bytes.reversedArray())

        private fun toLittleEndian(value: BigInteger, size: Int): ByteArray {
            val bigEndian = value.toByteArray().let { if (it.size > size) it.copyOfRange(it.size - size, it.size) else it }
            return (ByteArray(size - bigEndian.size) + bigEndian).reversedArray()
        }

        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}
