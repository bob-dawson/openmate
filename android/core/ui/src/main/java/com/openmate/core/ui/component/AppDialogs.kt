package com.openmate.core.ui.component

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.openmate.core.ui.theme.ProvideAppFontScale
import com.openmate.core.ui.theme.rememberAppFontScale

/**
 * Window-level wrappers that apply the in-app font size.
 *
 * Compose dialogs and bottom sheets live in their own window, which resets `LocalDensity` to the
 * system font scale. Every dialog must go through these wrappers so the user's size applies.
 */
@Composable
fun AppDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    val scale = rememberAppFontScale()
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        ProvideAppFontScale(factor = scale.factor) {
            content()
        }
    }
}

@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    val scale = rememberAppFontScale()
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            ProvideAppFontScale(factor = scale.factor) { confirmButton() }
        },
        modifier = modifier,
        dismissButton = dismissButton?.let {
            {
                ProvideAppFontScale(factor = scale.factor) { it() }
            }
        },
        icon = icon?.let {
            {
                ProvideAppFontScale(factor = scale.factor) { it() }
            }
        },
        title = title?.let {
            {
                ProvideAppFontScale(factor = scale.factor) { it() }
            }
        },
        text = text?.let {
            {
                ProvideAppFontScale(factor = scale.factor) { it() }
            }
        },
        shape = shape,
        containerColor = containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        properties = properties,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppModalBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scale = rememberAppFontScale()
    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState) {
        val scope: ColumnScope = this
        ProvideAppFontScale(factor = scale.factor) {
            scope.content()
        }
    }
}

@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    val scale = rememberAppFontScale()
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        scrollState = scrollState,
        properties = properties,
    ) {
        val scope: ColumnScope = this
        ProvideAppFontScale(factor = scale.factor) {
            scope.content()
        }
    }
}

@Composable
fun AppPopup(
    alignment: Alignment = Alignment.TopStart,
    offset: IntOffset = IntOffset.Zero,
    onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties = PopupProperties(),
    content: @Composable () -> Unit,
) {
    val scale = rememberAppFontScale()
    Popup(
        alignment = alignment,
        offset = offset,
        onDismissRequest = onDismissRequest,
        properties = properties,
    ) {
        ProvideAppFontScale(factor = scale.factor) {
            content()
        }
    }
}
