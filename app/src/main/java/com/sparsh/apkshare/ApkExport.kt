package com.sparsh.apkshare

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ApkFileProvider : FileProvider()

internal data class InstalledApp(
    val packageName: String,
    val label: String,
    val hasSplits: Boolean,
)

internal suspend fun PackageManager.installedApks(): List<InstalledApp> = withContext(Dispatchers.IO) {
    getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        .filter { it.sourceDir?.endsWith(".apk") == true }
        .map { app ->
            InstalledApp(
                packageName = app.packageName,
                label = app.loadLabel(this@installedApks).toString().ifBlank { app.packageName },
                hasSplits = !app.splitSourceDirs.isNullOrEmpty(),
            )
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { app: InstalledApp -> app.label }
            .thenBy { it.packageName })
}

internal suspend fun Context.apkShareIntent(packageName: String, zipRequested: Boolean): Intent =
    withContext(Dispatchers.IO) {
        val app = packageManager.getApplicationInfo(
            packageName,
            PackageManager.ApplicationInfoFlags.of(0),
        )
        val baseApk = app.sourceDir?.let(::File) ?: throw IOException("Base APK is unavailable")
        val splitApks = app.splitSourceDirs.orEmpty().map { File(it) }
        val asZip = zipRequested || splitApks.isNotEmpty()
        val cache = File(cacheDir, "apk-exports")
        Files.createDirectories(cache.toPath())

        // A receiver can still be uploading an earlier share when another one starts.
        val expiration = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
        cache.listFiles()?.filter { it.lastModified() < expiration }?.forEach { it.deleteRecursively() }

        val directory = Files.createTempDirectory(cache.toPath(), "share-").toFile()
        try {
            val extension = if (asZip) "zip" else "apk"
            val export = File(directory, "$packageName.$extension")
            if (asZip) {
                ZipOutputStream(export.outputStream().buffered()).use { archive ->
                    archive.setLevel(Deflater.NO_COMPRESSION)
                    archive.addApk(baseApk, "base.apk")
                    splitApks.forEach { archive.addApk(it, it.name) }
                }
            } else {
                baseApk.copyTo(export)
            }
            directory.setLastModified(System.currentTimeMillis())

            val uri = FileProvider.getUriForFile(
                this@apkShareIntent,
                "${this@apkShareIntent.packageName}.files",
                export,
            )
            Intent(Intent.ACTION_SEND).apply {
                // Advertise a standalone APK as a document so generic file targets can receive it.
                type = if (asZip) "application/zip" else "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(export.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (exception: Exception) {
            directory.deleteRecursively()
            throw exception
        }
    }

private fun ZipOutputStream.addApk(file: File, name: String) {
    putNextEntry(ZipEntry(name))
    file.inputStream().use { it.copyTo(this) }
    closeEntry()
}
