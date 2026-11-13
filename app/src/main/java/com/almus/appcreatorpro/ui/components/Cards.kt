package com.almus.appcreatorpro.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.almus.appcreatorpro.ui.theme.Spacing
import com.almus.appcreatorpro.ui.theme.almus

/**
 * The base card of the redesign: flat (no shadow), a hairline border and 16dp corners, which reads cleaner than stacked
 * shadows and costs less to draw. Pass [onClick] to make the whole card one tappable target.
 */
@Composable
fun AlmusCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    tone: CardTone = CardTone.Normal,
    contentPadding: PaddingValues = PaddingValues(Spacing.lg),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(Spacing.sm),
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val extras = MaterialTheme.almus
    val container: Color = when (tone) {
        CardTone.Normal -> extras.card
        CardTone.Error -> scheme.errorContainer
        CardTone.Success -> extras.successContainer
    }
    val contentColor: Color = when (tone) {
        CardTone.Normal -> scheme.onSurface
        CardTone.Error -> scheme.onErrorContainer
        CardTone.Success -> extras.onSuccessContainer
    }
    val colors = CardDefaults.cardColors(containerColor = container, contentColor = contentColor)
    val border = BorderStroke(1.dp, if (tone == CardTone.Normal) extras.cardBorder else Color.Transparent)
    val shape = MaterialTheme.shapes.large
    val inner: @Composable ColumnScope.() -> Unit = {
        Column(modifier = Modifier.padding(contentPadding), verticalArrangement = verticalArrangement) { content() }
    }
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors, border = border, content = inner)
    } else {
        Card(modifier = modifier, shape = shape, colors = colors, border = border, content = inner)
    }
}

/** A rounded square with an icon inside. Decorative: hidden from screen readers (the text next to it says the same). */
@Composable
fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    tint: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    size: Dp = 40.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(MaterialTheme.shapes.medium)
            .background(container)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(size / 2))
    }
}

/** One number with a label, for the dashboard. Read by screen readers as "Label: value". */
@Composable
fun StatTile(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    tint: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    AlmusCard(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "$label: $value" },
        contentPadding = PaddingValues(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        IconBadge(icon = icon, container = container, tint = tint, size = 36.dp)
        Text(text = value, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A tappable shortcut tile (icon + title + optional one-line hint). */
@Composable
fun ActionTile(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    emphasized: Boolean = false,
) {
    AlmusCard(
        modifier = modifier.heightIn(min = 72.dp),
        onClick = onClick,
        contentPadding = PaddingValues(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            IconBadge(
                icon = icon,
                container = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                tint = if (emphasized) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
