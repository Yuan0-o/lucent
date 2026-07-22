package com.lucent.desktop

import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * End-to-end proof that the desktop database is really encrypted at rest — run in CI by the
 * `:desktop:cipherSelfCheck` Gradle task (see build.gradle.kts) as a red/green gate before the
 * installer is packaged.
 *
 * Deliberately standalone: plain JDBC against a temp file, no app classes, no Context — so it
 * exercises exactly one thing, the driver Gradle resolved, and can never rot along with UI code.
 * It asserts the four claims that together mean "encrypted", in order of how they fail:
 *
 *  1. `PRAGMA cipher_version` answers with a value. Stock SQLite silently ignores unknown
 *     pragmas, so merely "accepting" key statements proves nothing — this is the one probe a
 *     plain driver cannot fake. Fails -> the resolved sqlite-jdbc is not the
 *     SQLite3MultipleCiphers build (someone swapped org.xerial back in, or the rich version
 *     constraint resolved somewhere unexpected).
 *  2. A database created under `PRAGMA cipher='sqlcipher'; legacy=4; key=x'…'` does NOT carry
 *     the plaintext "SQLite format 3" file header. Fails -> the pragmas ran but encryption
 *     never engaged.
 *  3. Re-opening with the SAME key reads the row back.
 *  4. Re-opening with a DIFFERENT key fails. Fails (i.e. the wrong key reads happily) -> the
 *     "encryption" is decorative.
 *
 * Exits non-zero (via the uncaught exception) on the first broken claim, which is what turns the
 * CI step red; prints one summary line when everything holds.
 */
fun main() {
    Class.forName("org.sqlite.JDBC")
    val file = File.createTempFile("lucent-cipher-check", ".db").apply { delete() }
    val rightKey = "x'" + "ab".repeat(32) + "'"
    val wrongKey = "x'" + "cd".repeat(32) + "'"

    fun open(): Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
    fun Connection.keyWith(key: String) = createStatement().use { st ->
        st.execute("PRAGMA cipher='sqlcipher'")
        st.execute("PRAGMA legacy=4")
        st.execute("PRAGMA key=\"$key\"")
    }

    try {
        // 1 + 2: cipher core present; create a keyed database and check its header on disk.
        open().use { c ->
            val version = c.createStatement().use { st ->
                st.executeQuery("PRAGMA cipher_version").use { rs -> if (rs.next()) rs.getString(1) else null }
            }
            require(!version.isNullOrBlank()) {
                "driver has no cipher core (PRAGMA cipher_version returned nothing) — the resolved " +
                    "sqlite-jdbc is NOT the SQLite3MultipleCiphers build"
            }
            println("cipher core present: SQLite3MultipleCiphers $version")
            c.keyWith(rightKey)
            c.createStatement().use { st ->
                st.executeUpdate("CREATE TABLE t(x TEXT)")
                st.executeUpdate("INSERT INTO t VALUES('lucent')")
            }
        }
        val head = ByteArray(16)
        file.inputStream().use { require(it.read(head) == head.size) { "database file is unreadably short" } }
        val plaintextHeader = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)
        require(!head.contentEquals(plaintextHeader)) {
            "the database file still starts with the plaintext SQLite header — encryption did NOT engage"
        }
        println("file header scrambled: not a plaintext SQLite file")

        // 3: the right key reads the row back.
        open().use { c ->
            c.keyWith(rightKey)
            val got = c.createStatement().use { st ->
                st.executeQuery("SELECT x FROM t").use { rs -> if (rs.next()) rs.getString(1) else null }
            }
            require(got == "lucent") { "keyed re-open read back '$got' instead of the stored row" }
        }
        println("right key reads the data back")

        // 4: a wrong key must NOT read anything.
        val wrongKeyWorked = try {
            open().use { c ->
                c.keyWith(wrongKey)
                c.createStatement().use { st -> st.executeQuery("SELECT count(*) FROM t").close() }
            }
            true
        } catch (_: Throwable) {
            false
        }
        require(!wrongKeyWorked) { "a WRONG key opened the database — the encryption is decorative" }
        println("wrong key rejected")

        println("CIPHER SELF-CHECK OK: at-rest encryption verified end to end.")
    } finally {
        file.delete()
    }
}
