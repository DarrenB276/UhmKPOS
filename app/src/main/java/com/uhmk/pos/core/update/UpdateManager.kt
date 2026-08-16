package com.uhmk.pos.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.uhmk.pos.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    val versionCode: Int?,
    val versionName: String,
    val title: String,
    val notes: String,
    val downloadUrl: String,
    val assetName: String,
)

enum class InstallLaunchResult {
    INSTALLER_OPENED,
    PERMISSION_REQUIRED,
}

/**
 * Small GitHub Releases updater for sideloaded builds.
 *
 * Release notes may include `Version code: 8`; when that marker is absent the updater falls back
 * to comparing the numeric parts of the release tag (for example v2.6.0).
 */
class UpdateManager(private val context: Context) {

    suspend fun checkForUpdate(): Result<AppUpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = connection(RELEASE_API_URL)
            val payload = try {
                check(connection.responseCode in 200..299) {
                    "GitHub returned ${connection.responseCode}"
                }
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }

            val release = parseRelease(payload)
            if (isNewer(release)) release else null
        }
    }

    suspend fun download(
        update: AppUpdateInfo,
        onProgress: (Int?) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val finalFile = File(updatesDir, "UhmKPOS-${update.versionName}.apk")
            val partialFile = File(updatesDir, "${finalFile.name}.part")
            partialFile.delete()

            val connection = connection(update.downloadUrl)
            try {
                check(connection.responseCode in 200..299) {
                    "Download failed with ${connection.responseCode}"
                }
                val expected = connection.contentLengthLong
                var copied = 0L
                connection.inputStream.use { input ->
                    partialFile.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            onProgress(
                                if (expected > 0) ((copied * 100L) / expected).toInt().coerceIn(0, 100)
                                else null
                            )
                        }
                    }
                }
                check(copied > 0L) { "The downloaded APK was empty" }
                if (finalFile.exists()) finalFile.delete()
                check(partialFile.renameTo(finalFile)) { "Could not finish the APK download" }
                onProgress(100)
                finalFile
            } finally {
                connection.disconnect()
                if (partialFile.exists()) partialFile.delete()
            }
        }
    }

    fun openInstaller(apk: File): InstallLaunchResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return InstallLaunchResult.PERMISSION_REQUIRED
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME_TYPE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
        return InstallLaunchResult.INSTALLER_OPENED
    }

    private fun parseRelease(payload: String): AppUpdateInfo {
        val json = JSONObject(payload)
        check(!json.optBoolean("draft", false)) { "The latest release is still a draft" }

        val tag = json.optString("tag_name").trim()
        val body = json.optString("body")
        val assets = json.getJSONArray("assets")
        val apkAssets = buildList {
            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                if (asset.optString("name").endsWith(".apk", ignoreCase = true)) add(asset)
            }
        }
        val asset = apkAssets.firstOrNull {
            it.optString("name").equals(PREFERRED_ASSET_NAME, ignoreCase = true)
        } ?: apkAssets.firstOrNull()
        checkNotNull(asset) { "This release does not contain an APK" }

        val versionName = cleanVersionName(tag.ifBlank { json.optString("name") })
        check(versionName.isNotBlank()) { "This release has no version number" }
        return AppUpdateInfo(
            versionCode = extractVersionCode(body, asset.optString("name"), tag),
            versionName = versionName,
            title = json.optString("name").ifBlank { "UhmK POS $versionName" },
            notes = stripVersionCodeMarker(body).ifBlank { "A new app update is ready." },
            downloadUrl = asset.getString("browser_download_url"),
            assetName = asset.getString("name"),
        )
    }

    private fun isNewer(update: AppUpdateInfo): Boolean {
        val remoteCode = update.versionCode
        return if (remoteCode != null) {
            remoteCode > BuildConfig.VERSION_CODE
        } else {
            compareVersions(update.versionName, BuildConfig.VERSION_NAME.substringBefore('-')) > 0
        }
    }

    private fun connection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "UhmKPOS/${BuildConfig.VERSION_NAME}")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }

    companion object {
        const val REPOSITORY_URL = "https://github.com/DarrenB276/UhmKPOS"
        private const val RELEASE_API_URL =
            "https://api.github.com/repos/DarrenB276/UhmKPOS/releases/latest"
        private const val PREFERRED_ASSET_NAME = "UhmKPOS-release.apk"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

        private val versionCodeLine =
            Regex("(?im)^\\s*version\\s+code\\s*:\\s*(\\d+)\\s*$")
        private val buildInName =
            Regex("(?i)(?:build|code|vc)[-_ ]?(\\d+)")

        internal fun extractVersionCode(body: String, assetName: String, tag: String): Int? =
            versionCodeLine.find(body)?.groupValues?.get(1)?.toIntOrNull()
                ?: buildInName.find(assetName)?.groupValues?.get(1)?.toIntOrNull()
                ?: buildInName.find(tag)?.groupValues?.get(1)?.toIntOrNull()

        internal fun stripVersionCodeMarker(body: String): String =
            body.lineSequence()
                .filterNot { versionCodeLine.matches(it) }
                .joinToString("\n")
                .trim()

        internal fun cleanVersionName(value: String): String =
            value.trim().removePrefix("v").removePrefix("V").substringBefore("-build")

        internal fun compareVersions(left: String, right: String): Int {
            val leftParts = Regex("\\d+").findAll(left).map { it.value.toInt() }.toList()
            val rightParts = Regex("\\d+").findAll(right).map { it.value.toInt() }.toList()
            for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
                val compared = (leftParts.getOrElse(index) { 0 })
                    .compareTo(rightParts.getOrElse(index) { 0 })
                if (compared != 0) return compared
            }
            return 0
        }
    }
}
