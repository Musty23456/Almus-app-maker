package com.almus.appcreatorpro.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.model.ProjectType
import com.almus.appcreatorpro.ui.theme.Spacing
import com.almus.appcreatorpro.util.Formatters

/** Letter tile for a project; the color tells the project type apart (decorative, the type is also written as text). */
@Composable
private fun ProjectAvatar(project: Project) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (project.type) {
        ProjectType.ANDROID_KOTLIN -> scheme.primaryContainer to scheme.onPrimaryContainer
        ProjectType.ANDROID_JAVA -> scheme.secondaryContainer to scheme.onSecondaryContainer
        ProjectType.WEB -> scheme.tertiaryContainer to scheme.onTertiaryContainer
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(container)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = project.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
            style = MaterialTheme.typography.titleMedium,
            color = content,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun ProjectCard(
    project: Project,
    nowMillis: Long,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    AlmusCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            ProjectAvatar(project)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (project.packageName.isBlank()) project.type.displayName
                    else "${project.type.displayName} - ${project.packageName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Created ${Formatters.formatDate(project.createdAtMillis)}  |  " +
                        "Updated ${Formatters.relativeTime(nowMillis, project.updatedAtMillis)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            trailing?.invoke()
        }
    }
}

@Composable
fun BuildCard(
    build: BuildRecord,
    nowMillis: Long,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    /** Optional content under the main row (Phase 4: APK availability and download actions). */
    extra: @Composable (() -> Unit)? = null,
) {
    AlmusCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = build.projectName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = build.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val duration = build.durationSeconds?.let { " - ${Formatters.formatDuration(it)}" }.orEmpty()
                Text(
                    text = Formatters.relativeTime(nowMillis, build.startedAtMillis) + duration,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BuildStatusPill(status = build.status)
        }
        extra?.invoke()
    }
}
