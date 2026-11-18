package com.sparsh.apkshare

import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val APK_TYPE = "application/vnd.android.package-archive"
private const val ZIP_TYPE = "application/zip"
private const val ALT_ZIP_TYPE = "application/x-zip-compressed"
private const val INSTALL_STATUS_ACTION = "com.sparsh.apkshare.INSTALL_STATUS"
private const val SESSION_ID = "com.sparsh.apkshare.SESSION_ID"

internal val installDocumentTypes = arrayOf(APK_TYPE, ZIP_TYPE, ALT_ZIP_TYPE, "application/octet-stream")

internal sealed interface InstallState {
    data object Idle : InstallState
    data object Preparing : InstallState
    data class Waiting(val sessionId: Int) : InstallState
    data class Review(val sessionId: Int, val confirmation: Intent) : InstallState
    data class Finished(@get:StringRes val message: Int, val details: String? = null) : InstallState
}

// The install session continues across activity recreation, using only the application context.
internal object ApkInstallation {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow<InstallState>(InstallState.Idle)
    val state = mutableState.asStateFlow()

    fun start(context: Context, document: Uri) {
        if (state.value !is InstallState.Idle && state.value !is InstallState.Finished) return
        mutableState.value = InstallState.Preparing
        val app = context.applicationContext
        scope.launch {
            val installer = app.packageManager.packageInstaller
            var sessionId: Int? = null
            try {
                val id = withContext(Dispatchers.IO) {
                    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                        setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
                        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                    }
                    installer.createSession(params)
                }
                sessionId = id
                withContext(Dispatchers.IO) {
                    installer.openSession(id).use { it.stageDocument(app.contentResolver, document) }
                }

                val callback = PendingIntent.getBroadcast(
                    app,
                    id,
                    Intent(app, InstallStatusReceiver::class.java)
                        .setAction(INSTALL_STATUS_ACTION)
                        .putExtra(SESSION_ID, id),
                    PendingIntent.FLAG_MUTABLE,
                )
                mutableState.value = InstallState.Waiting(id)
                try {
                    withContext(Dispatchers.IO) {
                        installer.openSession(id).use { it.commit(callback.intentSender) }
                    }
                } catch (exception: Exception) {
                    callback.cancel()
                    throw exception
                }
            } catch (exception: IOException) {
                fail(installer, sessionId, R.string.install_read_failed, exception.message)
            } catch (exception: SecurityException) {
                fail(installer, sessionId, R.string.install_blocked, exception.message)
            } catch (exception: IllegalArgumentException) {
                fail(installer, sessionId, R.string.install_failed, exception.message)
            } catch (exception: IllegalStateException) {
                fail(installer, sessionId, R.string.install_failed, exception.message)
            }
        }
    }

    fun confirm(activity: Activity) {
        val review = state.value as? InstallState.Review ?: return
        mutableState.value = InstallState.Waiting(review.sessionId)
        try {
            activity.startActivity(review.confirmation)
        } catch (_: ActivityNotFoundException) {
            fail(activity.packageManager.packageInstaller, review.sessionId, R.string.install_failed)
        } catch (_: SecurityException) {
            fail(activity.packageManager.packageInstaller, review.sessionId, R.string.install_blocked)
        }
    }

    fun cancel(context: Context) {
        val review = state.value as? InstallState.Review ?: return
        fail(context.packageManager.packageInstaller, review.sessionId, R.string.install_canceled)
    }

    fun onStatus(context: Context, intent: Intent) {
        val id = intent.getIntExtra(SESSION_ID, -1)
        if (intent.action != INSTALL_STATUS_ACTION || id == -1) return
        when (val current = state.value) {
            InstallState.Idle -> Unit
            is InstallState.Waiting -> if (current.sessionId != id) return
            is InstallState.Review -> if (current.sessionId != id) return
            else -> return
        }

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            if (confirmation != null) {
                mutableState.value = InstallState.Review(id, confirmation)
            } else {
                fail(context.packageManager.packageInstaller, id, R.string.install_failed)
            }
            return
        }

        val message = when (status) {
            PackageInstaller.STATUS_SUCCESS -> R.string.install_success
            PackageInstaller.STATUS_FAILURE_ABORTED -> R.string.install_canceled
            PackageInstaller.STATUS_FAILURE_BLOCKED -> R.string.install_blocked
            PackageInstaller.STATUS_FAILURE_STORAGE -> R.string.install_storage_failed
            else -> R.string.install_failed
        }
        val details = when (status) {
            PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> null
            else -> intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        }
        mutableState.value = InstallState.Finished(message, details)
    }

    private fun fail(installer: PackageInstaller, id: Int?, @StringRes message: Int, details: String? = null) {
        if (id != null) runCatching { installer.abandonSession(id) }
        mutableState.value = InstallState.Finished(message, details)
    }
}

class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = ApkInstallation.onStatus(context, intent)
}

private fun PackageInstaller.Session.stageDocument(resolver: ContentResolver, uri: Uri) {
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
    val asZip = when {
        name?.endsWith(".zip", ignoreCase = true) == true -> true
        name?.endsWith(".apk", ignoreCase = true) == true -> false
        else -> when (resolver.getType(uri)) {
            ZIP_TYPE, ALT_ZIP_TYPE -> true
            APK_TYPE -> false
            else -> throw IOException("Select an APK or ZIP file.")
        }
    }
    val stream = resolver.openInputStream(uri) ?: throw IOException("Could not open the selected file.")
    if (asZip) {
        ZipInputStream(stream.buffered()).use { archive ->
            var apkCount = 0
            while (true) {
                val entry = archive.nextEntry ?: break
                if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                    // Android identifies splits from their manifests. Archive paths never become output paths.
                    stageApk("part-${++apkCount}.apk", archive)
                }
                archive.closeEntry()
            }
            if (apkCount == 0) throw IOException("The ZIP does not contain any APK files.")
        }
    } else {
        stream.use { stageApk("base.apk", it) }
    }
}

private fun PackageInstaller.Session.stageApk(name: String, input: InputStream) {
    openWrite(name, 0, -1).use { output ->
        if (input.copyTo(output) == 0L) throw IOException("An APK in the selected file is empty.")
        fsync(output)
    }
}
