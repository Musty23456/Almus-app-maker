package com.almus.appcreatorpro.ui.artifacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.almus.appcreatorpro.service.artifacts.ArtifactEntry
import com.almus.appcreatorpro.service.artifacts.ArtifactReport
import com.almus.appcreatorpro.service.artifacts.InstallStatus
import com.almus.appcreatorpro.ui.components.BodyText
import com.almus.appcreatorpro.ui.components.CardTone
import com.almus.appcreatorpro.ui.components.SectionCard
import com.almus.appcreatorpro.util.Formatters

/** One saved APK/AAB: what it is, whether it can be trusted, and what can be done with it. */
@Composable
internal fun ArtifactCard(
    item: ArtifactEntry,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onVerify: () -> Unit,
    onDelete: () -> Unit,
    onOpenBuild: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val r = item.report
    var expanded by remember(item.key) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val danger = r != null && (r.integrity.blocksSharing || r.install.status == InstallStatus.NOT_INSTALLABLE)

    SectionCard(item.fileName, badge = ArtifactTexts.badge(item), tone = if (danger) CardTone.Error else CardTone.Normal) {
        BodyText("${item.projectName} - saved ${Formatters.formatDateTime(item.savedAtMillis)}")
        BodyText("Size: ${Formatters.formatBytes(item.sizeBytes)}")
        if (r == null) {
            BodyText("Checking this file...")
        } else {
            BodyText(ArtifactTexts.kindLine(r))
            BodyText(ArtifactTexts.packageLine(r))
            BodyText(ArtifactTexts.versionLine(r))
            ArtifactTexts.sdkLine(r)?.let { BodyText(it) }
            BodyText(ArtifactTexts.signingLine(r))
            BodyText(ArtifactTexts.integrityLine(r))
            val installColor = when (r.install.status) {
                InstallStatus.VERIFIED_INSTALLABLE -> MaterialTheme.colorScheme.primary
                InstallStatus.NOT_INSTALLABLE -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            }
            Text(r.install.headline, color = installColor, style = MaterialTheme.typography.titleSmall)
            if (r.install.detail.isNotEmpty()) BodyText(r.install.detail)
            r.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onShare, enabled = r != null) { Text("Share") }
            OutlinedButton(onClick = onExport, enabled = r != null) { Text("Save to...") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onVerify) { Text("Verify again") }
            TextButton(onClick = { expanded = !expanded }, enabled = r != null) { Text(if (expanded) "Hide details" else "Details") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onOpenBuild) { Text("Open build") }
            TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }

        if (expanded && r != null) {
            Details(r, onCopy = { label, text ->
                clipboard.setText(AnnotatedString(text))
                onMessage("$label copied")
            })
        }
    }
}

@Composable
private fun Details(r: ArtifactReport, onCopy: (String, String) -> Unit) {
    Text("Checksum", style = MaterialTheme.typography.titleSmall)
    BodyText("SHA-256 of the file: ${r.integrity.sha256 ?: "not available"}")
    if (r.integrity.recordedSha256 != null) {
        val source = if (r.integrity.recordedSource == "download") "when it was downloaded" else "when it was first checked"
        val at = r.integrity.recordedAtMillis?.let { " (" + Formatters.formatDateTime(it) + ")" }.orEmpty()
        BodyText("Recorded $source$at")
    }
    r.integrity.problems.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

    Text("Signature", style = MaterialTheme.typography.titleSmall)
    ArtifactTexts.schemesLine(r)?.let { BodyText(it) }
    if (r.signing.signers.isEmpty()) BodyText("No signing certificate to show.")
    r.signing.signers.forEach { s ->
        BodyText("Certificate: ${s.subject}")
        BodyText("Certificate SHA-256: ${s.sha256}")
    }
    BodyText("Only the public certificate is shown. Keys and passwords are never read here.")
    r.signing.problems.forEach { BodyText(it) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        r.integrity.sha256?.let { sha -> OutlinedButton(onClick = { onCopy("Checksum", sha) }) { Text("Copy checksum") } }
        OutlinedButton(onClick = { onCopy("Details", r.toPlainText()) }) { Text("Copy details") }
    }
}
