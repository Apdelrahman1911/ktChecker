package dev.ktchecker.kernel

import java.sql.DriverManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** Loaded dependency identifiers only; not source/binary authenticity or WAL-concurrency qualification. */
class KernelDependencyTest {
    @Test fun loadedJdbcDriverAndEmbeddedSQLiteReportPinnedVersions() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            val driverVersion = connection.metaData.driverVersion
            assertEquals("3.54.1.0", driverVersion, "Actual loaded JDBC driver differs from the intentional dependency pin")
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT sqlite_version(), sqlite_source_id()").use { row ->
                    assertTrue(row.next(), "Loaded SQLite did not return its runtime identifiers")
                    val sqliteVersion = row.getString(1)
                    val sourceId = assertNotNull(row.getString(2), "Loaded SQLite omitted its source identifier")
                    assertEquals("3.54.1", sqliteVersion, "Actual embedded SQLite differs from the intentional runtime pin")
                    // SQLite documents a check-in datetime followed by SHA3-256. An edited
                    // amalgamation can change the last four characters; no upstream hash is
                    // guessed or authenticated here. See sqlite.org/c3ref/libversion.html.
                    assertTrue(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} [0-9a-f]{60}[0-9a-z]{4}""")
                        .matches(sourceId), "Loaded SQLite returned an invalid source-identifier format")
                    val checkInFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
                        .withResolverStyle(ResolverStyle.STRICT)
                    assertTrue(runCatching { LocalDateTime.parse(sourceId.take(19), checkInFormat) }.isSuccess,
                        "Loaded SQLite returned an invalid source-identifier datetime")
                    assertFalse(row.next(), "Runtime identifier query returned unexpected extra rows")
                    println("kernel dependency smoke JDBC=$driverVersion sqlite_version=$sqliteVersion sqlite_source_id=$sourceId")
                }
            }
        }
    }
}
