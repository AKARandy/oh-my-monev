package id.ohmymonev.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateParserTest {
    @Test
    fun semanticVersionComparisonOrdersStableAndPreReleases() {
        val v110 = SemanticVersion.parse("1.1.0")!!
        val v111 = SemanticVersion.parse("v1.1.1")!!
        val v120Dev = SemanticVersion.parse("1.2.0-dev")!!
        val v120Dev1 = SemanticVersion.parse("1.2.0-dev.1")!!
        val v120Dev2 = SemanticVersion.parse("1.2.0-dev.2")!!
        val v120 = SemanticVersion.parse("v1.2.0")!!

        assertTrue(v111 > v110)
        assertTrue(v120Dev > v111)
        assertTrue(v120Dev1 > v120Dev)
        assertTrue(v120Dev2 > v120Dev1)
        assertTrue(v120 > v120Dev2)
        assertEquals(0, SemanticVersion.parse("v1.1.0")!!.compareTo(v110))
    }

    @Test
    fun parsesStableReleaseWithNativeDigestAndSha256SumsUrl() {
        val hex = "a".repeat(64)
        val payload = """
            {
              "tag_name": "v1.2.0",
              "name": "Oh My Monev v1.2.0",
              "body": "# Oh My Monev v1.2.0\n\nPembaruan baru dengan fitur mantap.",
              "draft": false,
              "assets": [
                {
                  "name": "oh-my-monev-v1.2.0.apk",
                  "size": 26500000,
                  "browser_download_url": "https://github.com/AKARandy/oh-my-monev/releases/download/v1.2.0/oh-my-monev-v1.2.0.apk",
                  "digest": "sha256:$hex"
                },
                {
                  "name": "SHA256SUMS.txt",
                  "size": 92,
                  "browser_download_url": "https://github.com/AKARandy/oh-my-monev/releases/download/v1.2.0/SHA256SUMS.txt"
                }
              ]
            }
        """.trimIndent()

        val release = AppUpdateParser.parseGitHubRelease(payload, isDevBuild = false)
        assertNotNull(release)
        assertEquals("v1.2.0", release!!.tag)
        assertEquals("1.2.0", release.versionName)
        assertEquals("oh-my-monev-v1.2.0.apk", release.apkName)
        assertEquals(hex, release.expectedSha256)
        assertNotNull(release.sha256Url)
        assertEquals("Pembaruan baru dengan fitur mantap.", release.summary())
        assertTrue(AppUpdateParser.isNewer("1.1.0", release))
        assertFalse(AppUpdateParser.isNewer("1.2.0", release))
        assertFalse(AppUpdateParser.isNewer("1.3.0", release))
    }

    @Test
    fun extractsVersionFromDevTitleAndRejectsUntrustedHttpUrl() {
        val devPayload = """
            {
              "tag_name": "dev",
              "name": "Oh My Monev Dev (1.2.0-dev.1)",
              "body": "Build uji coba.",
              "assets": [
                {
                  "name": "oh-my-monev-dev.apk",
                  "size": 30000000,
                  "browser_download_url": "https://github.com/AKARandy/oh-my-monev/releases/download/dev/oh-my-monev-dev.apk"
                }
              ]
            }
        """.trimIndent()
        val devRelease = AppUpdateParser.parseGitHubRelease(devPayload, isDevBuild = true)
        assertNotNull(devRelease)
        assertEquals("1.2.0-dev.1", devRelease!!.versionName)
        assertTrue(AppUpdateParser.isNewer("1.1.0-dev", devRelease))

        val badUrlPayload = """
            {
              "tag_name": "v1.2.0",
              "assets": [
                {
                  "name": "oh-my-monev-v1.2.0.apk",
                  "size": 100,
                  "browser_download_url": "http://evil.example.com/app.apk"
                }
              ]
            }
        """.trimIndent()
        assertNull(AppUpdateParser.parseGitHubRelease(badUrlPayload, isDevBuild = false))
    }

    @Test
    fun parsesSha256SumsFileAccurately() {
        val hash = "0123456789abcdef".repeat(4)
        val content = "$hash  oh-my-monev-v1.2.0.apk\n"
        assertEquals(hash, AppUpdateParser.parseSha256Sums(content, "oh-my-monev-v1.2.0.apk"))
        assertNull(AppUpdateParser.parseSha256Sums(content, "other.apk"))
        assertNull(AppUpdateParser.parseSha256Sums("not-a-hash  oh-my-monev-v1.2.0.apk", "oh-my-monev-v1.2.0.apk"))
    }
}
