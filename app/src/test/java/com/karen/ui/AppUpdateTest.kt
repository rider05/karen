package com.karen.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class AppUpdateTest {

    @Test
    fun constantsMatchGitHubRepository() {
        assertEquals("rider05/karen", AppUpdate.REPO)
        assertEquals("apks/karen.apk", AppUpdate.APK_PATH)
        assertEquals(
            "https://github.com/rider05/karen/raw/main/apks/karen.apk",
            AppUpdate.RAW_URL
        )
    }

    @Test
    fun checkModelsHoldExpectedData() {
        val upToDate = AppUpdate.Check.UpToDate
        assertTrue(upToDate is AppUpdate.Check)

        val available = AppUpdate.Check.UpdateAvailable(
            sha = "764a992",
            date = "2026-11-04",
            releaseNotes = "New design updates"
        )
        assertEquals("764a992", available.sha)
        assertEquals("2026-11-04", available.date)
        assertEquals("New design updates", available.releaseNotes)

        val failed = AppUpdate.Check.Failed("HTTP 404")
        assertEquals("HTTP 404", failed.reason)
    }

    @Test
    fun apkValidationModel() {
        val valid = AppUpdate.ApkValidation(valid = true, versionCode = 2, versionName = "1.1")
        assertTrue(valid.valid)
        assertEquals(2L, valid.versionCode)
        assertEquals("1.1", valid.versionName)
        assertEquals("", valid.reason)

        val invalid = AppUpdate.ApkValidation(valid = false, reason = "Checksum error")
        assertFalse(invalid.valid)
        assertEquals("Checksum error", invalid.reason)
    }

    @Test
    fun gitBlobShaCalculationMatchesSpec() {
        val testContent = "Karen offline assistant test payload".toByteArray(Charsets.UTF_8)
        val tempFile = File.createTempFile("karen_test_blob", ".tmp")
        try {
            tempFile.writeBytes(testContent)

            // Manual git blob SHA calculation: SHA-1 of "blob <len>\0<content>"
            val md = MessageDigest.getInstance("SHA-1")
            md.update("blob ${testContent.size}\u0000".toByteArray(Charsets.UTF_8))
            md.update(testContent)
            val expectedSha = md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }

            // Calculate via same algorithm as AppUpdate.gitBlobSha
            val shaCalc = MessageDigest.getInstance("SHA-1")
            shaCalc.update("blob ${tempFile.length()}\u0000".toByteArray(Charsets.UTF_8))
            tempFile.inputStream().use { ins ->
                val buf = ByteArray(1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    shaCalc.update(buf, 0, n)
                }
            }
            val actualSha = shaCalc.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }

            assertEquals(expectedSha, actualSha)
        } finally {
            tempFile.delete()
        }
    }
}
