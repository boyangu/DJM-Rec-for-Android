package com.audiopro.djmrec.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Single-select chip row. Used for sample rate, mixer capture level and the silence hold time.
 *
 * Generic in the value type so callers can key it on an Int (a rate) or a Long (a duration in
 * milliseconds) without converting back and forth.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> OptionChips(
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                enabled = enabled,
                onClick = { onSelect(value) },
                label = { Text(label) },
                modifier = Modifier.heightIn(min = 40.dp)
            )
        }
    }
}
