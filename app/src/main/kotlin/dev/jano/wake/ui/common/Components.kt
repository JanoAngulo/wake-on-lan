package dev.jano.wake.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.jano.wake.ui.icons.WakeIcons
import dev.jano.wake.ui.theme.WakeColors
import dev.jano.wake.ui.theme.WakeShapes
import dev.jano.wake.ui.theme.WakeType

/** Plain dark screen with a back arrow and a scrolling body, used by setup and settings. */
@Composable
fun ScreenScaffold(title: String, onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(WakeColors.Background).imePadding()) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape)
                        .clickable(role = Role.Button, onClickLabel = "Back", onClick = onBack)
                        .semantics { contentDescription = "Back" },
                    contentAlignment = Alignment.Center,
                ) { Icon(WakeIcons.Back, null, tint = WakeColors.OnSurface, modifier = Modifier.size(22.dp)) }
            } else {
                Spacer(Modifier.size(16.dp))
            }
            Text(title, style = WakeType.Title, color = WakeColors.OnSurface, modifier = Modifier.padding(start = 4.dp).semantics { heading() })
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            content()
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
fun BodyText(text: String, modifier: Modifier = Modifier) =
    Text(text, style = WakeType.Body, color = WakeColors.OnSurfaceMuted, modifier = modifier)

@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    busy: Boolean = false,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val active = enabled && !busy
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(WakeShapes.Button)
            .background(if (active) WakeColors.HeroDisc else WakeColors.Surface)
            .clickable(enabled = active, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), color = WakeColors.OnSurface, strokeWidth = 2.dp)
            Spacer(Modifier.size(12.dp))
        } else if (icon != null) {
            Icon(icon, null, tint = if (active) WakeColors.Background else WakeColors.OnSurfaceMuted, modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(10.dp))
        }
        Text(label, style = WakeType.Button, color = if (active) WakeColors.Background else WakeColors.OnSurfaceMuted)
    }
}

@Composable
fun SecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(WakeShapes.Button)
            .background(if (danger) WakeColors.DangerContainer else WakeColors.Surface)
            .border(1.dp, if (danger) WakeColors.DangerOutline else WakeColors.Outline, WakeShapes.Button)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) { Text(label, style = WakeType.Button, color = if (danger) WakeColors.Danger else WakeColors.OnSurface) }
}

/** Tappable row inside a card: title, optional subtitle, optional trailing content. */
@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = { Icon(WakeIcons.ChevronRight, null, tint = WakeColors.OnSurfaceMuted, modifier = Modifier.size(20.dp)) },
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = WakeColors.OnSurfaceMuted, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = WakeType.RowValue, color = WakeColors.OnSurface)
            if (subtitle != null) Text(subtitle, style = WakeType.RowSecondary, color = WakeColors.OnSurfaceMuted)
        }
        if (onClick != null && trailing != null) trailing()
    }
}

/** Labelled field with persistent helper text; the error replaces it once the field has been left. */
@Composable
fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    helper: String? = null,
    error: String? = null,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    onBlur: () -> Unit = {},
    password: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            isError = error != null,
            keyboardOptions = keyboard,
            visualTransformation = if (password && !shown) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = if (password) {
                {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape)
                            .clickable(role = Role.Button, onClickLabel = if (shown) "Hide password" else "Show password") { shown = !shown }
                            .semantics { contentDescription = if (shown) "Hide password" else "Show password" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (shown) WakeIcons.EyeOff else WakeIcons.Eye, null, tint = WakeColors.OnSurfaceMuted, modifier = Modifier.size(22.dp))
                    }
                }
            } else {
                null
            },
            textStyle = WakeType.RowValue,
            shape = WakeShapes.Button,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = WakeColors.HeroDisc,
                unfocusedBorderColor = WakeColors.Outline,
                focusedLabelColor = WakeColors.HeroDisc,
                unfocusedLabelColor = WakeColors.OnSurfaceMuted,
                cursorColor = WakeColors.HeroDisc,
                focusedTextColor = WakeColors.OnSurface,
                unfocusedTextColor = WakeColors.OnSurface,
                errorBorderColor = WakeColors.Danger,
                errorLabelColor = WakeColors.Danger,
                focusedContainerColor = WakeColors.Surface,
                unfocusedContainerColor = WakeColors.Surface,
                errorContainerColor = WakeColors.Surface,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged {
                    if (focused && !it.isFocused) onBlur()
                    focused = it.isFocused
                }
                .then(if (error != null) Modifier.semantics { error(error) } else Modifier),
        )
        val note = error ?: helper
        if (note != null) {
            Text(note, style = WakeType.Note, color = if (error != null) WakeColors.Danger else WakeColors.OnSurfaceMuted, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable
fun SectionLabel(text: String) =
    Text(text, style = WakeType.Section, color = WakeColors.OnSurfaceMuted, modifier = Modifier.padding(start = 8.dp, top = 12.dp).semantics { heading() })
