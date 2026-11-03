package com.almus.appcreatorpro.ui.signing

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.almus.appcreatorpro.domain.build.SigningProfile
import com.almus.appcreatorpro.domain.build.SigningValidator
import com.almus.appcreatorpro.ui.AppViewModelProvider
import com.almus.appcreatorpro.ui.components.BodyText
import com.almus.appcreatorpro.ui.components.CardTone
import com.almus.appcreatorpro.ui.components.SectionCard
import java.io.ByteArrayOutputStream

/** Reads at most [limit] bytes; returns null when the file is bigger (it cannot be a keystore). */
private fun readLimited(context: android.content.Context, uri: android.net.Uri, limit: Int): ByteArray? =
    context.contentResolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > limit) return null
            out.write(buffer, 0, n)
        }
        out.toByteArray()
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SigningScreen(
    onBack: () -> Unit,
    viewModel: SigningViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val snackbar = remember { SnackbarHostState() }
    var showCreate by remember { mutableStateOf(false) }
    var importBytes by remember { mutableStateOf<ByteArray?>(null) }
    var backupFor by remember { mutableStateOf<SigningProfile?>(null) }
    var deleteFor by remember { mutableStateOf<SigningProfile?>(null) }

    // Keys and passwords are shown on this screen: keep them out of screenshots and the recent-apps preview.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    val pickKeystore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val bytes = try { readLimited(context, uri, SigningValidator.MAX_KEYSTORE_BYTES) } catch (e: Exception) { null }
            if (bytes == null || bytes.isEmpty()) viewModel.showError("That file cannot be read or is too large to be a keystore.") else importBytes = bytes
        }
    }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val profile = backupFor
        backupFor = null
        if (uri != null && profile != null) viewModel.backup(profile) { context.contentResolver.openOutputStream(uri) }
    }

    LaunchedEffect(state.info, state.error) {
        (state.error ?: state.info)?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    if (showCreate) CreateKeyDialog(
        suggestPassword = viewModel::suggestPassword,
        busy = state.busy,
        onDismiss = { showCreate = false },
        onCreate = { label, alias, pw, name, org, country ->
            viewModel.generate(label, alias, pw, name, org, country)
            showCreate = false
        },
    )
    importBytes?.let { bytes ->
        ImportKeyDialog(
            onDismiss = { importBytes = null },
            onImport = { label, alias, storePw, keyPw ->
                viewModel.import(label, bytes, alias, storePw, keyPw)
                importBytes = null
            },
        )
    }
    deleteFor?.let { p ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete \"${p.label}\"?") },
            text = { Text("The key is removed from this phone only. If you have no backup, apps signed with it can never be updated. Your GitHub secrets are not touched.") },
            confirmButton = { TextButton(onClick = { viewModel.delete(p); deleteFor = null }) { Text("Delete key") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Keep") } },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Release signing") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard("How signing stays safe") {
                BodyText("Your key and passwords are encrypted on this phone. They are never written into your project, never uploaded to your repository and never printed in a build log.")
                BodyText("For cloud builds you add them once as GitHub Actions Secrets. Only the separate \"sign\" job can read them, and it runs none of your project's code.")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showCreate = true }, enabled = !state.busy) { Text(if (state.busy) "Working..." else "Create key") }
                OutlinedButton(onClick = { pickKeystore.launch(arrayOf("*/*")) }, enabled = !state.busy) { Text("Import key") }
            }
            if (state.profiles.isEmpty()) BodyText("No keys yet. Create a new key, or import the keystore you already use for your app.")
            state.profiles.forEach { p ->
                SectionCard(p.label, badge = if (p.verifiedOnDevice) null else "UNVERIFIED") {
                    BodyText("${p.source.label} - alias ${p.alias}")
                    p.sha256?.let { Text("SHA-256: $it", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    if (!p.verifiedOnDevice) BodyText("This phone cannot open JKS files, so the build checks this key in the cloud.")
                    BodyText(
                        if (p.secretsConfirmedFor.isEmpty()) "Secrets not confirmed for any repository yet."
                        else "Secrets confirmed for: ${p.secretsConfirmedFor.joinToString(", ")}",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.reveal(p) }) { Text("Show secrets") }
                        OutlinedButton(onClick = { backupFor = p; backupLauncher.launch("${p.alias}-keystore.p12") }) { Text("Back up") }
                    }
                    TextButton(onClick = { deleteFor = p }) { Text("Delete key") }
                }
            }
            state.revealed?.let { (profile, secrets) ->
                SecretsCard(
                    profile = profile,
                    entries = secrets.entries(),
                    onCopy = { value -> clipboard.setText(AnnotatedString(value)) },
                    onConfirm = { repo -> viewModel.confirmSecrets(profile, repo) },
                    onOpenGitHub = { repo -> uriHandler.openUri("https://github.com/$repo/settings/secrets/actions/new") },
                    onHide = viewModel::hideSecrets,
                )
            }
        }
    }
}

@Composable
private fun SecretsCard(
    profile: SigningProfile,
    entries: List<Pair<String, String>>,
    onCopy: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onOpenGitHub: (String) -> Unit,
    onHide: () -> Unit,
) {
    var repo by remember { mutableStateOf("") }
    val repoOk = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(repo.trim())
    SectionCard("GitHub secrets for \"${profile.label}\"", tone = CardTone.Success) {
        BodyText("In your repository: Settings > Secrets and variables > Actions > New repository secret. Add these four, one by one. Copy each value with its button (the clipboard is cleared by Android after a while; do not paste it anywhere else).")
        entries.forEach { (name, value) ->
            Text(name, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
            BodyText(if (name.endsWith("BASE64")) "Keystore file as text (${value.length} characters)" else "Hidden. Use Copy.")
            OutlinedButton(onClick = { onCopy(value) }) { Text("Copy value") }
        }
        OutlinedTextField(
            value = repo,
            onValueChange = { repo = it },
            label = { Text("Repository (owner/name)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onOpenGitHub(repo.trim()) }, enabled = repoOk) { Text("Open GitHub") }
            Button(onClick = { onConfirm(repo.trim()) }, enabled = repoOk) { Text("I added the secrets") }
        }
        TextButton(onClick = onHide) { Text("Hide secrets") }
    }
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, error: String? = null) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = if (error != null) { { Text(error) } } else null,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
        trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show") } },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CreateKeyDialog(
    suggestPassword: () -> String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (label: String, alias: String, password: String, name: String, organization: String, country: String) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("release") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var org by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("") }
    val pwError = if (password.isEmpty()) null else SigningValidator.validateNewPassword(password)
    val ready = !busy && SigningValidator.validateLabel(label) == null && SigningValidator.validateAlias(alias) == null &&
        password.isNotEmpty() && pwError == null && name.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create a signing key") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("Name for this key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(alias, { alias = it }, label = { Text("Alias") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                PasswordField(password, { password = it }, "Password (12+ characters)", pwError)
                TextButton(onClick = { password = suggestPassword() }) { Text("Generate a strong password") }
                BodyText("Write the password down in a safe place. If you lose the password or the key, apps signed with it cannot be updated.")
                OutlinedTextField(name, { name = it }, label = { Text("Your name or company") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(org, { org = it }, label = { Text("Organization (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(country, { country = it }, label = { Text("Country code, e.g. NG (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(label, alias, password, name, org, country) }, enabled = ready) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ImportKeyDialog(
    onDismiss: () -> Unit,
    onImport: (label: String, alias: String, storePassword: String, keyPassword: String) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("") }
    var storePw by remember { mutableStateOf("") }
    var keyPw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import a keystore") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("Name for this key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(alias, { alias = it }, label = { Text("Key alias") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                BodyText("Leave the alias empty if the file has only one key (PKCS12 / .p12 / .pfx). A JKS file needs the alias.")
                PasswordField(storePw, { storePw = it }, "Keystore password")
                PasswordField(keyPw, { keyPw = it }, "Key password (empty = same)")
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(label, alias.trim(), storePw, keyPw) }, enabled = label.isNotBlank() && storePw.isNotEmpty()) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
