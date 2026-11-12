package com.example.cartoon

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** The GitHub repository that updates and feedback go to. */
const val GITHUB_REPO = "Dappermanphoto/CarToon"

data class ReleaseInfo(
    val tag: String,
    val version: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

enum class InstallStart { STARTED, NEEDS_PERMISSION, FAILED }

/**
 * Checks the newest GitHub release, downloads its APK and hands it to Android's installer.
 * Android always asks the person to confirm an install, so this can't be fully silent.
 */
object Updater {
    fun installedVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
    }.getOrNull() ?: "0"

    private fun parts(v: String): List<Int> =
        v.trim().trimStart('v', 'V').split('.', '-', '_').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    fun isNewer(remote: String, local: String): Boolean {
        val a = parts(remote)
        val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "CarToon-Updater")
        }

    /** Null means the repository has no release yet. */
    suspend fun latest(): Result<ReleaseInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = open("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
            try {
                if (conn.responseCode == 404) return@runCatching null
                if (conn.responseCode != 200) error("GitHub answered ${conn.responseCode}")
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val tag = json.optString("tag_name")
                var apkUrl = ""
                var size = 0L
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                            apkUrl = a.optString("browser_download_url")
                            size = a.optLong("size")
                            break
                        }
                    }
                }
                ReleaseInfo(tag, tag.trimStart('v', 'V'), json.optString("body"), apkUrl, size)
            } finally {
                conn.disconnect()
            }
        }
    }

    suspend fun download(context: Context, info: ReleaseInfo, onProgress: (Float) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "updates").apply {
                    mkdirs()
                    listFiles()?.forEach { it.delete() }
                }
                val out = File(dir, "CarToon-v${info.version}.apk")
                val conn = open(info.apkUrl)
                try {
                    if (conn.responseCode != 200) error("Download answered ${conn.responseCode}")
                    val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
                    var done = 0L
                    var lastPct = -1
                    conn.inputStream.use { input ->
                        out.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        onProgress(pct / 100f)
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                if (info.sizeBytes > 0 && out.length() != info.sizeBytes) error("Download incomplete")
                out
            }
        }

    /** false = the APK is signed with a different key than the installed app (Android would refuse it). */
    fun sameSigner(context: Context, apk: File): Boolean? = runCatching {
        val pm = context.packageManager
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val fresh = pm.getPackageArchiveInfo(apk.path, flags)?.signingInfo?.apkContentsSigners
            ?: return@runCatching null
        val current = pm.getPackageInfo(context.packageName, flags).signingInfo?.apkContentsSigners
            ?: return@runCatching null
        fresh.map { it.toCharsString() }.toSet() == current.map { it.toCharsString() }.toSet()
    }.getOrNull()

    fun install(context: Context, apk: File): InstallStart {
        if (!context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return InstallStart.NEEDS_PERMISSION
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (runCatching { context.startActivity(intent) }.isSuccess) InstallStart.STARTED
        else InstallStart.FAILED
    }
}
