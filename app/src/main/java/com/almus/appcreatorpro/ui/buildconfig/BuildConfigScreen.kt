package com.almus.appcreatorpro.ui.buildconfig

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.almus.appcreatorpro.domain.build.BuildVariant
import com.almus.appcreatorpro.domain.build.OutputFormat
import com.almus.appcreatorpro.ui.AppViewModelProvider
import com.almus.appcreatorpro.ui.components.BodyText
import com.almus.appcreatorpro.ui.components.CardTone
import com.almus.appcreatorpro.ui.components.SectionCard

/** Build configuration manager for one project: build type, package, versions, SDK levels, options and signing key. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildConfigScreen(
    projectId: String,
    onBack: () -> Unit,
    onOpenSigning: () -> Unit,
    viewModel: BuildConfigViewModel = viewModel(factory = AppViewModelProvider.buildConfigFactory(projectId)),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = s.config
    // Coming back from the signing screen: pick up a newly created key.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Build configuration") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard("Build type", tone = if (s.hasErrors) CardTone.Error else CardTone.Normal) {
                BuildVariant.entries.forEach { v ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = c.variant == v, onClick = { viewModel.setVariant(v) })
                        Column {
                            Text(v.label, style = MaterialTheme.typography.titleSmall)
                            BodyText(v.summary)
                        }
                    }
                }
                if (s.hasErrors) BodyText("Fix the highlighted fields. Nothing is saved until they are valid.")
            }

            if (c.variant == BuildVariant.CUSTOM) {
                SectionCard("Custom build") {
                    Text("Output", style = MaterialTheme.typography.titleSmall)
                    OutputFormat.entries.forEach { o ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = c.customOutput == o, onClick = { viewModel.setCustomOutput(o) })
                            Text(o.label)
                        }
                    }
                    ToggleRow("Release build type", c.customRelease, viewModel::setCustomRelease)
                    if (c.customRelease) ToggleRow("Sign with my release key", c.customSign, viewModel::setCustomSign)
                    BodyText("Gradle task: ${c.gradleTask}")
                }
            }

            SectionCard("Application") {
                BodyText("Leave a field empty to keep the value from your project. The values are applied during the cloud build; your files are not edited.")
                Field("Application ID / package name", s.applicationId, viewModel::onApplicationId, s.errors["applicationId"], KeyboardType.Ascii)
                Field("Version name", s.versionName, viewModel::onVersionName, s.errors["versionName"], KeyboardType.Ascii)
                Field("Version code", s.versionCode, viewModel::onVersionCode, s.errors["versionCode"], KeyboardType.Number)
                Field("Minimum SDK", s.minSdk, viewModel::onMinSdk, s.errors["minSdk"], KeyboardType.Number)
                Field("Target SDK", s.targetSdk, viewModel::onTargetSdk, s.errors["targetSdk"], KeyboardType.Number)
            }

            SectionCard("Build options") {
                TriRow("Shrink code (minify)", c.options.minify, viewModel::setMinify)
                TriRow("Shrink resources", c.options.shrinkResources, viewModel::setShrink)
                s.errors["options"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                BodyText("\"Project\" keeps what your build file says.")
            }

            if (c.needsReleaseSigning) {
                SectionCard("Release signing", tone = if (c.signingProfileId == null) CardTone.Error else CardTone.Normal) {
                    if (s.profiles.isEmpty()) BodyText("You have no signing key yet. Create or import one first.")
                    s.profiles.forEach { p ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = c.signingProfileId == p.id, onClick = { viewModel.setSigningProfile(p.id) })
                            Column {
                                Text(p.label, style = MaterialTheme.typography.titleSmall)
                                BodyText("alias ${p.alias}" + if (p.secretsConfirmedFor.isEmpty()) " - secrets not confirmed" else " - secrets confirmed")
                            }
                        }
                    }
                    OutlinedButton(onClick = onOpenSigning) { Text("Manage signing keys") }
                }
            }

            TextButton(onClick = viewModel::resetAll) { Text("Reset to defaults (debug APK)") }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, error: String?, type: KeyboardType) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = if (error != null) { { Text(error) } } else null,
        keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrect = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Project default / On / Off. */
@Composable
private fun TriRow(label: String, value: Boolean?, onChange: (Boolean?) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf<Pair<String, Boolean?>>("Project" to null, "On" to true, "Off" to false).forEach { (text, v) ->
                RadioButton(selected = value == v, onClick = { onChange(v) })
                Text(text, modifier = Modifier.padding(end = 12.dp))
            }
        }
    }
}
