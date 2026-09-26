package com.audiopro.djmrec.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.audiopro.djmrec.ui.theme.SrColor

/*
 * Android settings rows from the SET REC design system (Settings.dc.html): M3 list items on a
 * black ground, monochrome controls. One row per preference; no cards.
 */

private val TitleStyle = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal)
private val SummaryStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
private val ValueStyle = TextStyle(
    fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium
)
private val ButtonShape = RoundedCornerShape(10.dp)

@Composable
fun SettingsSectionHeader(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
            .semantics { heading() },
        style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
        color = SrColor.TextSecondary
    )
}

/** A boxed note above the list, e.g. why controls are locked. */
@Composable
fun SettingsNote(text: String, warn: Boolean = false) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            .background(SrColor.Surface, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        style = SummaryStyle,
        color = if (warn) SrColor.Warn else SrColor.TextSecondary
    )
}

/**
 * The settings row. [value] sits right-aligned on the title line (a slider's reading); [below]
 * holds an inline control such as a segmented button or slider. With [onClick] the whole row is a
 * button; [rowModifier] lets callers make it toggleable instead.
 */
@Composable
fun Preference(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    summaryWarn: Boolean = false,
    value: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val clickModifier = if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    else Modifier
    val titleColor = if (enabled) SrColor.TextPrimary else SrColor.DisabledContent
    val secondaryColor = if (enabled) SrColor.TextSecondary else SrColor.DisabledContent
    Row(
        modifier.fillMaxWidth().then(clickModifier).heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, Modifier.weight(1f), style = TitleStyle, color = titleColor)
                if (value != null) Text(value, style = ValueStyle, color = secondaryColor)
            }
            if (summary != null) {
                Text(
                    summary, style = SummaryStyle,
                    color = if (summaryWarn && enabled) SrColor.Warn else secondaryColor
                )
            }
            if (below != null) Column(Modifier.fillMaxWidth().padding(top = 12.dp), content = below)
        }
        trailing?.invoke(this)
    }
}

@Composable
fun SwitchPreference(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    summaryWarn: Boolean = false,
    enabled: Boolean = true,
) {
    Preference(
        title = title,
        summary = summary,
        summaryWarn = summaryWarn,
        enabled = enabled,
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        trailing = { SrSwitch(checked, enabled) }
    )
}

/** Display-only M3 switch; the row it sits in handles the toggle and the semantics. */
@Composable
private fun SrSwitch(checked: Boolean, enabled: Boolean) {
    Switch(
        checked = checked,
        onCheckedChange = null,
        enabled = enabled,
        thumbContent = if (checked) {
            { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }
        } else null,
        colors = SwitchDefaults.colors(
            checkedThumbColor = SrColor.OnInverse,
            checkedTrackColor = SrColor.Inverse,
            checkedBorderColor = SrColor.Inverse,
            checkedIconColor = SrColor.Inverse,
            uncheckedThumbColor = SrColor.LineStrong,
            uncheckedTrackColor = SrColor.SurfaceRaised,
            uncheckedBorderColor = SrColor.LineStrong,
            disabledCheckedThumbColor = SrColor.OnInverse.copy(alpha = 0.38f),
            disabledCheckedTrackColor = SrColor.Inverse.copy(alpha = 0.38f),
            disabledCheckedBorderColor = Color.Transparent,
            disabledCheckedIconColor = SrColor.Inverse.copy(alpha = 0.38f),
            disabledUncheckedThumbColor = SrColor.LineStrong.copy(alpha = 0.38f),
            disabledUncheckedTrackColor = SrColor.SurfaceRaised.copy(alpha = 0.38f),
            disabledUncheckedBorderColor = SrColor.LineStrong.copy(alpha = 0.38f),
        )
    )
}

/**
 * Android's ListPreference: the row shows the current choice; tapping it opens a radio dialog
 * that applies on tap. [summary] maps the chosen label to the row's summary.
 */
@Composable
fun <T> ListPreference(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
    summary: (String) -> String = { it },
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == selected }?.second.orEmpty()
    Preference(title = title, summary = summary(label), enabled = enabled, onClick = { open = true })
    if (open && enabled) ChoiceDialog(title, options, selected, onSelect) { open = false }
}

@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            shape = RoundedCornerShape(28.dp),
            color = SrColor.SurfaceRaised,
            contentColor = SrColor.TextPrimary
        ) {
            Column(Modifier.padding(top = 24.dp, bottom = 16.dp)) {
                Text(
                    title,
                    Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                    style = TextStyle(fontSize = 24.sp, lineHeight = 32.sp)
                )
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).selectableGroup()) {
                    options.forEach { (value, label) ->
                        val on = value == selected
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(selected = on, role = Role.RadioButton) { onSelect(value); onDismiss() }
                                .padding(horizontal = 24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            RadioButton(
                                selected = on, onClick = null,
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = SrColor.Inverse, unselectedColor = SrColor.TextSecondary
                                )
                            )
                            Text(label, style = TitleStyle)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp), horizontalArrangement = Arrangement.End) {
                    GhostButton("Cancel", onClick = onDismiss)
                }
            }
        }
    }
}

/** M3 single-select segmented button, full width. */
@Composable
fun <T> SrSegmentedButton(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().semantics { contentDescription = label }) {
        options.forEachIndexed { index, (value, text) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = SrColor.Tonal,
                    activeContentColor = SrColor.TextPrimary,
                    activeBorderColor = SrColor.LineStrong,
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = SrColor.TextPrimary,
                    inactiveBorderColor = SrColor.LineStrong,
                    disabledActiveContainerColor = SrColor.DisabledFill,
                    disabledActiveContentColor = SrColor.DisabledContent,
                    disabledActiveBorderColor = SrColor.DisabledFill,
                    disabledInactiveContainerColor = Color.Transparent,
                    disabledInactiveContentColor = SrColor.DisabledContent,
                    disabledInactiveBorderColor = SrColor.DisabledFill,
                ),
                label = { Text(text, maxLines = 1) }
            )
        }
    }
}

/** Integer slider with the M3 bar handle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SrSlider(
    value: Int,
    range: IntRange,
    enabled: Boolean,
    label: String,
    valueText: String,
    onChange: (Int) -> Unit,
) {
    val colors = SliderDefaults.colors(
        thumbColor = SrColor.Inverse,
        activeTrackColor = SrColor.Inverse,
        inactiveTrackColor = SrColor.SurfaceRaised,
        activeTickColor = Color.Transparent,
        inactiveTickColor = Color.Transparent,
        disabledThumbColor = SrColor.DisabledContent,
        disabledActiveTrackColor = SrColor.DisabledContent,
        disabledInactiveTrackColor = SrColor.DisabledFill,
        disabledActiveTickColor = Color.Transparent,
        disabledInactiveTickColor = Color.Transparent,
    )
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(Math.round(it)) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = range.last - range.first - 1,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = label
            stateDescription = valueText
        },
        colors = colors,
        // The design's track has no end dot.
        track = { SliderDefaults.Track(sliderState = it, colors = colors, enabled = enabled, drawStopIndicator = null) }
    )
}

/** An action that belongs to the row above it (e.g. "Allow access" under a switch). */
@Composable
fun PreferenceAction(indent: Boolean = false, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(start = if (indent) 32.dp else 16.dp, end = 16.dp, bottom = 12.dp)) { content() }
}

@Composable
fun SrOutlinedButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = ButtonShape,
        border = BorderStroke(1.dp, if (enabled) SrColor.LineStrong else SrColor.DisabledFill),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = SrColor.TextPrimary, disabledContentColor = SrColor.DisabledContent
        ),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.heightIn(min = 40.dp)
    ) { Text(text) }
}

@Composable
fun SrFilledButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled, shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = SrColor.Inverse, contentColor = SrColor.OnInverse,
            disabledContainerColor = SrColor.DisabledFill, disabledContentColor = SrColor.DisabledContent
        ),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.heightIn(min = 40.dp)
    ) { Text(text) }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(
        onClick = onClick, enabled = enabled, shape = ButtonShape,
        colors = ButtonDefaults.textButtonColors(
            contentColor = SrColor.TextPrimary, disabledContentColor = SrColor.DisabledContent
        ),
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = Modifier.heightIn(min = 40.dp)
    ) { Text(text) }
}
