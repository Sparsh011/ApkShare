package com.sparsh.apkshare

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.sparsh.apkshare.ui.theme.ApkShareTheme
import java.io.IOException
import kotlinx.coroutines.launch

private const val SAVED_PICKER_OPEN = "picker_open"
private const val SAVED_SHARED_ZIP = "shared_zip"

class MainActivity : ComponentActivity() {
    private var pickerOpen by mutableStateOf(false)
    private var sharedZip by mutableStateOf<Uri?>(null)
    private var incomingShareError by mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            receiveShare(intent)
        } else {
            pickerOpen = savedInstanceState.getBoolean(SAVED_PICKER_OPEN)
            sharedZip = savedInstanceState.getParcelable(SAVED_SHARED_ZIP, Uri::class.java)
        }
        enableEdgeToEdge()
        setContent {
            ApkShareTheme { ApkShareScreen() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveShare(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(SAVED_PICKER_OPEN, pickerOpen)
        outState.putParcelable(SAVED_SHARED_ZIP, sharedZip)
        super.onSaveInstanceState(outState)
    }

    private fun receiveShare(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        pickerOpen = false
        val zip = intent.sharedZipUri()
        incomingShareError = when {
            zip == null -> R.string.install_shared_zip_invalid
            !ApkInstallation.state.value.canStart -> R.string.install_shared_zip_busy
            else -> {
                sharedZip = zip
                null
            }
        }
    }

    private fun installSharedZip() {
        val zip = sharedZip ?: return
        if (ApkInstallation.start(this, zip, sharedZip = true)) sharedZip = null
    }

    @Composable
    private fun ApkShareScreen() {
        var query by rememberSaveable { mutableStateOf("") }
        var shareAsZip by rememberSaveable { mutableStateOf(false) }
        var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
        var preparing by remember { mutableStateOf(false) }
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current

        LaunchedEffect(pickerOpen) {
            if (pickerOpen) apps = packageManager.installedApks()
        }
        BackHandler(enabled = pickerOpen) {
            if (!preparing) pickerOpen = false
        }

        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { insets ->
            Box(Modifier.fillMaxSize().padding(insets)) {
                if (pickerOpen) {
                    AppPicker(
                        apps = apps,
                        query = query,
                        onQueryChange = { query = it },
                        shareAsZip = shareAsZip,
                        onZipChange = { shareAsZip = it },
                        preparing = preparing,
                        onBack = { pickerOpen = false },
                        onAppSelected = { app ->
                            if (!preparing) {
                                preparing = true
                                focus.clearFocus()
                                keyboard?.hide()
                                scope.launch {
                                    val error = try {
                                        val shareIntent = apkShareIntent(app.packageName, shareAsZip)
                                        startActivity(Intent.createChooser(shareIntent, null))
                                        null
                                    } catch (_: PackageManager.NameNotFoundException) {
                                        apps = apps?.filterNot { it.packageName == app.packageName }
                                        getString(R.string.app_removed, app.label)
                                    } catch (_: IOException) {
                                        getString(R.string.share_error, app.label)
                                    } catch (_: SecurityException) {
                                        getString(R.string.share_error, app.label)
                                    } catch (_: ActivityNotFoundException) {
                                        getString(R.string.share_error, app.label)
                                    } finally {
                                        preparing = false
                                    }
                                    if (error != null) snackbar.showSnackbar(error)
                                }
                            }
                        },
                    )
                } else {
                    Column(
                        modifier = Modifier.align(Alignment.Center)
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                        Text(stringResource(R.string.share_description), style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = {
                            query = ""
                            apps = null
                            pickerOpen = true
                        }) {
                            Text(stringResource(R.string.share_apk_file))
                        }
                        InstallControls()
                    }
                }
            }
        }
    }

    @Composable
    private fun InstallControls() {
        val installState by ApkInstallation.state.collectAsState()
        var pickerError by rememberSaveable { mutableStateOf<Int?>(null) }
        var permissionForSharedZip by rememberSaveable { mutableStateOf(false) }
        val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { document ->
            if (document != null) ApkInstallation.start(this, document)
        }
        fun chooseFile() {
            try {
                documentPicker.launch(installDocumentTypes)
            } catch (_: ActivityNotFoundException) {
                pickerError = R.string.install_picker_unavailable
            }
        }
        val unknownSources = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (packageManager.canRequestPackageInstalls()) {
                if (sharedZip != null) installSharedZip()
                else if (!permissionForSharedZip) chooseFile()
            } else {
                pickerError = R.string.install_permission_required
            }
        }

        LaunchedEffect(sharedZip) {
            if (sharedZip != null) {
                pickerError = null
                if (packageManager.canRequestPackageInstalls()) installSharedZip()
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = installState.canStart,
                onClick = {
                    pickerError = null
                    incomingShareError = null
                    if (packageManager.canRequestPackageInstalls()) {
                        if (sharedZip != null) installSharedZip() else chooseFile()
                    } else {
                        permissionForSharedZip = sharedZip != null
                        try {
                            val source = Uri.fromParts("package", packageName, null)
                            unknownSources.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, source))
                        } catch (_: ActivityNotFoundException) {
                            pickerError = R.string.install_permission_required
                        }
                    }
                },
            ) {
                val label = if (sharedZip != null) R.string.install_shared_zip else R.string.install_apk_or_zip
                Text(stringResource(label))
            }
            if (sharedZip != null) {
                TextButton(onClick = {
                    sharedZip = null
                    pickerError = null
                }) { Text(stringResource(R.string.install_cancel)) }
            }

            incomingShareError?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }

            val error = pickerError
            if (error != null) {
                Text(stringResource(error), style = MaterialTheme.typography.bodyMedium)
            } else {
                when (val state = installState) {
                    InstallState.Idle -> Unit
                    InstallState.Preparing, is InstallState.Waiting -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        val message = when (state) {
                            InstallState.Preparing -> R.string.install_preparing
                            else -> R.string.install_waiting
                        }
                        Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
                    }
                    is InstallState.Review -> {
                        Text(stringResource(R.string.install_review))
                        Button(onClick = { ApkInstallation.confirm(this@MainActivity) }) {
                            Text(stringResource(R.string.install_continue))
                        }
                        TextButton(onClick = { ApkInstallation.cancel(this@MainActivity) }) {
                            Text(stringResource(R.string.install_cancel))
                        }
                    }
                    is InstallState.Finished -> {
                        Text(stringResource(state.message), style = MaterialTheme.typography.bodyMedium)
                        if (!state.details.isNullOrBlank()) Text(state.details, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppPicker(
    apps: List<InstalledApp>?,
    query: String,
    onQueryChange: (String) -> Unit,
    shareAsZip: Boolean,
    onZipChange: (Boolean) -> Unit,
    preparing: Boolean,
    onBack: () -> Unit,
    onAppSelected: (InstalledApp) -> Unit,
) {
    val visibleApps = remember(apps, query) {
        val search = query.trim()
        apps?.filter {
            it.label.contains(search, ignoreCase = true) || it.packageName.contains(search, ignoreCase = true)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, enabled = !preparing) { Text(stringResource(R.string.back)) }
            Text(stringResource(R.string.installed_apps), style = MaterialTheme.typography.titleLarge)
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            label = { Text(stringResource(R.string.search_apps)) },
            enabled = !preparing,
            singleLine = true,
        )
        Row(
            modifier = Modifier.fillMaxWidth()
                .toggleable(value = shareAsZip, enabled = !preparing, role = Role.Switch, onValueChange = onZipChange)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.share_as_zip), Modifier.weight(1f).padding(end = 12.dp))
            Switch(checked = shareAsZip, onCheckedChange = null, enabled = !preparing)
        }
        if (preparing) {
            Text(stringResource(R.string.preparing_share), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        if (preparing || visibleApps == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        HorizontalDivider()
        if (visibleApps?.isEmpty() == true) {
            val message = if (apps.isNullOrEmpty()) R.string.no_apps else R.string.no_matches
            Text(stringResource(message), Modifier.padding(16.dp))
        }
        if (visibleApps != null) {
            LazyColumn {
                items(visibleApps, key = { it.packageName }) { app ->
                    ListItem(
                        headlineContent = { Text(app.label) },
                        supportingContent = {
                            Text(app.packageName)
                            if (app.hasSplits) Text(stringResource(R.string.split_package))
                        },
                        modifier = Modifier.clickable(enabled = !preparing, role = Role.Button) { onAppSelected(app) },
                    )
                }
            }
        }
    }
}
