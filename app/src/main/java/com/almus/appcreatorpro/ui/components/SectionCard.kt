package com.almus.appcreatorpro.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** A titled card used by the Phase 3 screens. [tone] colors it for warnings/errors. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    tone: CardTone = CardTone.Normal,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val container: Color = when (tone) {
        CardTone.Normal -> scheme.surface
        CardTone.Error -> scheme.errorContainer
        CardTone.Success -> scheme.secondaryContainer
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                if (badge != null) PrototypeBadge(label = badge)
            }
            content()
        }
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
