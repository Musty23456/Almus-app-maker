package com.almus.appcreatorpro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.almus.appcreatorpro.domain.model.BuildStatus
import com.almus.appcreatorpro.ui.theme.Spacing
import com.almus.appcreatorpro.ui.theme.almus

/** Small pill that marks a feature as unfinished so it is never mistaken for a working one. */
@Composable
fun PrototypeBadge(
    modifier: Modifier = Modifier,
    label: String = "PROTOTYPE",
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.almus.warningContainer,
        contentColor = MaterialTheme.almus.onWarningContainer,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (badge != null) PrototypeBadge(label = badge)
        Spacer(modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Centered spinner with a message. Announced politely to screen readers. For list screens prefer [SkeletonList]. */
@Composable
fun LoadingView(
    modifier: Modifier = Modifier,
    message: String = "Loading...",
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(strokeWidth = 3.dp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A large soft circle with an icon, shared by the empty and error states. */
@Composable
private fun StateIcon(icon: ImageVector, container: Color, tint: Color) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = tint)
    }
}

@Composable
fun EmptyView(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StateIcon(icon, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(modifier = Modifier.height(Spacing.lg))
        Text(text = title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(Spacing.lg))
            AlmusButton(text = actionLabel, onClick = onAction, icon = Icons.Filled.Add)
        }
    }
}

@Composable
fun ErrorView(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Something went wrong",
    retryLabel: String = "Try again",
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StateIcon(Icons.Filled.Warning, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            Spacer(modifier = Modifier.height(Spacing.lg))
            Text(text = title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(Spacing.lg))
        AlmusButton(text = retryLabel, onClick = onRetry, style = AlmusButtonStyle.Outlined, icon = Icons.Filled.Refresh)
    }
}

/** A compact error shown inside a screen that still has content (a failed refresh, one failed section). */
@Composable
fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    AlmusCard(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive },
        tone = CardTone.Error,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Icon(imageVector = Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(text = message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (onRetry != null) AlmusButton(text = "Retry", onClick = onRetry, style = AlmusButtonStyle.Text)
        }
    }
}

/** Colors, icon and words for one build status. The words and icon mean status is never shown by color alone. */
private data class StatusLook(val container: Color, val content: Color, val icon: ImageVector, val label: String)

@Composable
fun BuildStatusPill(status: BuildStatus, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val extras = MaterialTheme.almus
    val look = when (status) {
        BuildStatus.SUCCESS -> StatusLook(extras.successContainer, extras.onSuccessContainer, Icons.Filled.CheckCircle, "Success")
        BuildStatus.FAILED -> StatusLook(scheme.errorContainer, scheme.onErrorContainer, Icons.Filled.Warning, "Failed")
        BuildStatus.RUNNING -> StatusLook(scheme.primaryContainer, scheme.onPrimaryContainer, Icons.Filled.Refresh, "Running")
        BuildStatus.QUEUED -> StatusLook(scheme.surfaceContainerHighest, scheme.onSurfaceVariant, Icons.Filled.Info, "Queued")
        BuildStatus.PREPARING -> StatusLook(scheme.secondaryContainer, scheme.onSecondaryContainer, Icons.Filled.Build, "Preparing")
        BuildStatus.CANCELLED -> StatusLook(scheme.surfaceContainerHigh, scheme.onSurfaceVariant, Icons.Filled.Close, "Cancelled")
    }
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = look.container,
        contentColor = look.content,
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(imageVector = look.icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(text = look.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}
