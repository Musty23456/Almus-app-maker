package com.almus.appcreatorpro.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** A titled card used by most screens. [tone] colors it for warnings/errors. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    tone: CardTone = CardTone.Normal,
    content: @Composable () -> Unit,
) {
    AlmusCard(modifier = modifier.fillMaxWidth(), tone = tone) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            if (badge != null) PrototypeBadge(label = badge)
        }
        content()
    }
}

enum class CardTone { Normal, Error, Success }

@Composable
fun BodyText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
