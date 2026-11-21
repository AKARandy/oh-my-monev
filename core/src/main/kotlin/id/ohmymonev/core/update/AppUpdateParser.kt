package id.ohmymonev.core.update

import java.net.URI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        val a = preRelease
        val b = other.preRelease
        if (a == b) return 0
        if (a == null) return 1
        if (b == null) return -1
        val aParts = a.split('.')
        val bParts = b.split('.')
        val count = minOf(aParts.size, bParts.size)
        for (i in 0 until count) {
            val ap = aParts[i]
            val bp = bParts[i]
            if (ap == bp) continue
            val an = ap.toIntOrNull()
            val bn = bp.toIntOrNull()
            return when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1
                bn != null -> 1
                else -> ap.compareTo(bp)
            }
        }
        return aParts.size.compareTo(bParts.size)
    }

    override fun toString(): String = buildString {
        append(major).append('.').append(minor).append('.').append(patch)
        if (preRelease != null) append('-').append(preRelease)
    }

    companion object {
        private val VERSION_REGEX = Regex("""^[vV]?(\d+)\.(\d+)(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?$""")
        private val EMBEDDED_REGEX = Regex("""[vV]?(\d+\.\d+(?:\.\d+)?(?:-[0-9A-Za-z.-]+)?)""")

        fun parse(raw: String): SemanticVersion? {
            val match = VERSION_REGEX.matchEntire(raw.trim()) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].ifEmpty { "0" }.toIntOrNull() ?: return null
            val pre = match.groupValues[4].takeIf { it.isNotEmpty() }
            return SemanticVersion(major, minor, patch, pre)
        }

        fun extract(raw: String): SemanticVersion? =
            parse(raw) ?: EMBEDDED_REGEX.find(raw)?.groupValues?.get(1)?.let(::parse)
    }
}

data class UpdateRelease(
    val tag: String,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val apkSizeBytes: Long,
    val expectedSha256: String?,
    val sha256Url: String?,
) {
    /** Short plain-text summary (1–2 lines) suitable for an inline banner. */
    fun summary(maxLines: Int = 2, maxChars: Int = 160): String {
        val cleaned = notes
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("---") }
            .map { it.replace("**", "").replace("`", "") }
            .take(maxLines)
            .joinToString(" ")
        return if (cleaned.length <= maxChars) cleaned else cleaned.take(maxChars - 1).trimEnd() + "…"
    }
}

object AppUpdateParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val sha256Hex = Regex("^[0-9a-fA-F]{64}$")
    private val trustedHosts = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "api.github.com",
    )

    @Serializable
    private data class GitHubReleaseDto(
        @SerialName("tag_name") val tagName: String = "",
        val name: String = "",
        val body: String? = null,
        val draft: Boolean = false,
        val assets: List<GitHubAssetDto> = emptyList(),
    )

    @Serializable
    private data class GitHubAssetDto(
        val name: String = "",
        val size: Long = 0L,
        @SerialName("browser_download_url") val downloadUrl: String = "",
        val digest: String? = null,
    )

    fun isTrustedDownloadUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return host in trustedHosts || host.endsWith(".githubusercontent.com")
    }

    fun parseGitHubRelease(jsonText: String, isDevBuild: Boolean): UpdateRelease? {
        val dto = runCatching { json.decodeFromString<GitHubReleaseDto>(jsonText) }.getOrNull() ?: return null
        if (dto.draft || dto.tagName.isBlank()) return null

        val apkAssets = dto.assets.filter {
            it.name.endsWith(".apk", ignoreCase = true) && isTrustedDownloadUrl(it.downloadUrl)
        }
        val chosen = if (isDevBuild) {
            apkAssets.firstOrNull { it.name.contains("dev", ignoreCase = true) } ?: apkAssets.firstOrNull()
        } else {
            apkAssets.firstOrNull { !it.name.contains("-dev", ignoreCase = true) } ?: apkAssets.firstOrNull()
        } ?: return null

        val parsedVersion = SemanticVersion.parse(dto.tagName) ?: SemanticVersion.extract(dto.name)
        val versionName = parsedVersion?.toString() ?: dto.tagName.removePrefix("v").removePrefix("V")

        val digestHex = chosen.digest
            ?.removePrefix("sha256:")
            ?.trim()
            ?.takeIf { sha256Hex.matches(it) }
            ?.lowercase()

        val sha256Asset = dto.assets.firstOrNull {
            it.name.equals("SHA256SUMS.txt", ignoreCase = true) && isTrustedDownloadUrl(it.downloadUrl)
        }

        return UpdateRelease(
            tag = dto.tagName,
            versionName = versionName,
            notes = dto.body.orEmpty().trim(),
            apkUrl = chosen.downloadUrl,
            apkName = chosen.name,
            apkSizeBytes = chosen.size.coerceAtLeast(0L),
            expectedSha256 = digestHex,
            sha256Url = sha256Asset?.downloadUrl,
        )
    }

    fun parseSha256Sums(content: String, targetFileName: String): String? {
        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("\\s+"), limit = 2)
            if (parts.size != 2) continue
            val hash = parts[0].trim()
            val file = parts[1].trim().removePrefix("*").substringAfterLast('/')
            if (file.equals(targetFileName, ignoreCase = true) && sha256Hex.matches(hash)) {
                return hash.lowercase()
            }
        }
        return null
    }

    fun isNewer(currentVersionName: String, candidate: UpdateRelease): Boolean {
        val current = SemanticVersion.parse(currentVersionName) ?: return false
        val next = SemanticVersion.parse(candidate.versionName) ?: return false
        return next > current
    }
}
