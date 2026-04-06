/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (Android-only)
 */
package com.xiaomo.androidforclaw.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xiaomo.androidforclaw.R
import com.xiaomo.androidforclaw.config.OpenClawConfig

/**
 * 渠道模型choose器 — 从 OpenClaw 已Config的 providers DynamicReadAvailable模型List. 
 *
 * 该 Composable 不做任何Persistent化, Statusbycall方Manage. 
 *
 * @param config  当Front OpenClawConfig, 用于Read providers
 * @param selected 当Front选中的模型 ID(格式 "providerId/modelId"), null Table示useGlobalDefault
 * @param onSelected UserchooseBack的Callback
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelModelPicker(
    config: OpenClawConfig,
    selected: String?,
    onSelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    // BuildAvailable模型List: null → GlobalDefault；Its他项from已Config providers
    val globalDefaultLabel = stringResource(R.string.picker_use_global)
    val models: List<Pair<String?, String>> = remember(config, globalDefaultLabel) {
        val list = mutableListOf<Pair<String?, String>>()
        list.add(null to globalDefaultLabel)
        config.resolveProviders().forEach { (providerId, providerCfg) ->
            providerCfg.models.forEach { model ->
                val key = "$providerId/${model.id}"
                val label = "$providerId / ${model.name.ifEmpty { model.id }}"
                list.add(key to label)
            }
        }
        list
    }

    var expanded by remember { mutableStateOf(false) }

    val currentLabel = models.find { it.first == selected }?.second
        ?: selected
        ?: globalDefaultLabel

    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.picker_model_override),
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(Modifier.height(4.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = currentLabel,
                onValueChange = {},
                readOnly = true,
                trailingIcon = {
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                label = { Text(stringResource(R.string.picker_model_label)) }
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                models.forEach { (key, label) ->
                    DropdownMenuItem(
                        text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                        onClick = {
                            onSelected(key)
                            expanded = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }
        if (models.size <= 1) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.picker_no_provider),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
