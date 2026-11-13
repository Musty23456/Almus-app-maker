package com.almus.appcreatorpro.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.almus.appcreatorpro.ui.theme.Dimens

enum class AlmusButtonStyle { Primary, Tonal, Outlined, Text, Danger }

/**
 * The one button used by new and redesigned screens: 48dp minimum height (touch target), 12dp corners, an optional leading
 * icon and a loading state. While [loading] the button ignores taps and announces "Loading" to screen readers, so a double
 * tap cannot start an action twice.
 */
@Composable
fun AlmusButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: AlmusButtonStyle = AlmusButtonStyle.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val active = enabled && !loading
    val buttonModifier = modifier
        .heightIn(min = Dimens.MinTouchTarget)
        .then(if (loading) Modifier.semantics { stateDescription = "Loading" } else Modifier)
    val shape = MaterialTheme.shapes.medium
    val content: @Composable RowScope.() -> Unit = {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
            Spacer(modifier = Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    when (style) {
        AlmusButtonStyle.Primary -> Button(onClick = onClick, modifier = buttonModifier, enabled = active, shape = shape, content = content)
        AlmusButtonStyle.Tonal -> FilledTonalButton(onClick = onClick, modifier = buttonModifier, enabled = active, shape = shape, content = content)
        AlmusButtonStyle.Outlined -> OutlinedButton(onClick = onClick, modifier = buttonModifier, enabled = active, shape = shape, content = content)
        AlmusButtonStyle.Text -> TextButton(onClick = onClick, modifier = buttonModifier, enabled = active, shape = shape, content = content)
        AlmusButtonStyle.Danger -> Button(
            onClick = onClick,
            modifier = buttonModifier,
            enabled = active,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            content = content,
        )
    }
}
