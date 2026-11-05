package com.almus.appcreatorpro.ui.github

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.almus.appcreatorpro.domain.github.AuthMethod
import com.almus.appcreatorpro.service.github.GitHubAuthState
import com.almus.appcreatorpro.ui.AppViewModelProvider
import com.almus.appcreatorpro.ui.components.BodyText
import com.almus.appcreatorpro.ui.components.CardTone
import com.almus.appcreatorpro.ui.components.ConfirmDialog
import com.almus.appcreatorpro.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubConnectionScreen(
    onBack: () -> Unit,
    onBrowseRepositories: () -> Unit,
    viewModel: GitHubConnectionViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmDisconnect by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GitHub connection") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val s = authState) {
                is GitHubAuthState.Connected -> ConnectedCard(
                    s = s,
                    onBrowse = onBrowseRepositories,
                    onDisconnect = { confirmDisconnect = true },
                )
                is GitHubAuthState.Unverified -> SectionCard("Saved sign-in not verified", tone = CardTone.Error) {
                    BodyText(
                        "A sign-in for ${s.username} is saved on this phone, but GitHub could not confirm it is still valid " +
                            "(${s.reason}). You are NOT treated as connected until GitHub confirms it.",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::recheck) { Text("Check again") }
                        OutlinedButton(onClick = { confirmDisconnect = true }) { Text("Remove") }
                    }
                }
                GitHubAuthState.Verifying -> SectionCard("Checking with GitHub...") {
                    CircularProgressIndicator()
                }
                is GitHubAuthState.AwaitingAuthorization -> AwaitingCard(s, onCancel = viewModel::cancel)
                is GitHubAuthState.Expired -> {
                    SectionCard("Sign-in expired", tone = CardTone.Error) {
                        BodyText(
                            "GitHub no longer accepts the saved sign-in${s.username?.let { " for $it" }.orEmpty()}. " +
                                "The old token was deleted from this phone. Connect again below.",
                        )
                    }
                    SignInForm(form, viewModel)
                }
                is GitHubAuthState.Failed -> {
                    SectionCard("Sign-in failed", tone = CardTone.Error) { BodyText(s.message) }
                    SignInForm(form, viewModel)
                }
                GitHubAuthState.Disconnected -> SignInForm(form, viewModel)
            }
            HowItWorksCard()
        }
    }

    if (confirmDisconnect) {
        ConfirmDialog(
            title = "Disconnect GitHub?",
            message = "This deletes the saved sign-in (token) from this phone. Your repositories and builds on GitHub are not touched. " +
                "To also revoke the token on GitHub, remove the app under github.com > Settings > Applications.",
            confirmLabel = "Disconnect",
            destructive = true,
            onConfirm = {
                confirmDisconnect = false
                viewModel.disconnect()
            },
            onDismiss = { confirmDisconnect = false },
        )
    }
}

@Composable
private fun ConnectedCard(s: GitHubAuthState.Connected, onBrowse: () -> Unit, onDisconnect: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    SectionCard("Connected as ${s.user.login}", tone = CardTone.Success) {
        BodyText("GitHub confirmed this sign-in just now.")
        BodyText("Access: ${s.access.describe()}")
        BodyText(
            if (s.access.canCreateRepository) {
                "You can create repositories from the repository picker."
            } else {
                "This sign-in cannot create repositories; create one on github.com (or choose an existing one)."
            },
        )
        Button(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) { Text("Browse repositories") }
        OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Disconnect and delete token") }
        TextButton(onClick = { uriHandler.openUri("https://github.com/settings/applications") }) {
            Text("Review authorized apps on GitHub")
        }
    }
}

@Composable
private fun AwaitingCard(s: GitHubAuthState.AwaitingAuthorization, onCancel: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    SectionCard("Approve on github.com") {
        BodyText("1. Tap \"Open GitHub\". 2. Enter this code. 3. Approve. Then come back to this screen.")
        Text(
            text = s.userCode,
            style = MaterialTheme.typography.headlineLarge,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 4.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(s.userCode)) }) { Text("Copy code") }
            Button(onClick = { uriHandler.openUri(s.verificationUri) }) { Text("Open GitHub") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(modifier = Modifier.padding(4.dp))
            BodyText("Waiting for you to approve. The code is valid for about 15 minutes.")
        }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun SignInForm(form: ConnectionForm, viewModel: GitHubConnectionViewModel) {
    SectionCard("Sign in") {
        BodyText("Choose how much access to give. The first option gives the least.")
        AuthMethod.entries.forEach { method ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = form.method == method, onClick = { viewModel.onMethodChange(method) }, role = Role.RadioButton)
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadioButton(selected = form.method == method, onClick = null)
                Column {
                    Text(method.label, style = MaterialTheme.typography.bodyLarge)
                    BodyText(method.summary)
                }
            }
        }
        OutlinedTextField(
            value = form.clientId,
            onValueChange = viewModel::onClientIdChange,
            label = { Text("Client ID of your GitHub app") },
            singleLine = true,
            isError = form.clientIdError != null,
            supportingText = { Text(form.clientIdError ?: "Public identifier, not a secret. See \"One-time setup\" below.") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = viewModel::connect, modifier = Modifier.fillMaxWidth()) { Text("Connect to GitHub") }
    }
}

@Composable
private fun HowItWorksCard() {
    SectionCard("How sign-in works and what it can do") {
        BodyText(
            "This app uses GitHub's \"device flow\": you get a short code, approve it on github.com, and GitHub gives the app " +
                "an access token. No password is typed into the app and no client secret exists anywhere in it.",
        )
        BodyText(
            "The token is encrypted with a key in the Android Keystore and never written to logs, files or build artifacts. " +
                "It is used only by this app to call GitHub; it is never sent to your build workflow, so your project's " +
                "Gradle scripts cannot read it.",
        )
        Text("One-time setup (on github.com)", style = MaterialTheme.typography.titleSmall)
        BodyText(
            "GitHub App (recommended): Settings > Developer settings > GitHub Apps > New. Repository permissions: Contents = " +
                "Read and write, Actions = Read and write, Metadata = Read-only. Tick \"Enable Device Flow\". Untick " +
                "\"Expire user authorization tokens\" (or just reconnect every 8 hours). Create it, copy the Client ID, then " +
                "Install App on the repository you will build in.",
        )
        BodyText(
            "OAuth App (simpler, broader): Settings > Developer settings > OAuth Apps > New. Tick \"Enable Device Flow\", " +
                "copy the Client ID. The access level you pick above decides the scope (public_repo or repo).",
        )
        Text("Limits", style = MaterialTheme.typography.titleSmall)
        BodyText(
            "Anyone who gets into an unlocked copy of this phone's app data could use the token while it is valid. " +
                "Disconnect removes it from the phone; revoke it on GitHub (Settings > Applications) to invalidate it everywhere.",
        )
    }
}
