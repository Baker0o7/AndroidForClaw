/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropnext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xiaomo.androidforclaw.R
import com.xiaomo.androidforclaw.config.OpenClawconfig

/**
 * 渠道模型choose器 — from OpenClaw alreadyconfig providers DynamicReadAvailable模型List. 
 *
 * should Composable not做任何Persistent化, Statusbycall方Manage. 
 *
 * @param config  whenFront OpenClawconfig, 用于Read providers
 * @param selected whenFront选中模型 ID(format "providerId/modelId"), null Table示useGlobalDefault
 * @param onSelected userchoosebackCallback
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun channelmodelPicker(
    config: OpenClawconfig,
    selected: String?,
    onSelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    // BuildAvailable模型List: null → GlobalDefault；Its他项fromalreadyconfig providers
    val globalDefaultLabel = stringResource(R.string.picker_use_global)
    val models: List<Pair<String?, String>> = remember(config, globalDefaultLabel) {
        val list = mutableListOf<Pair<String?, String>>()
        list.a(null to globalDefaultLabel)
        config.resolveproviders().forEach { (providerId, providerCfg) ->
            providerCfg.models.forEach { model ->
                val key = "$providerId/${model.id}"
                val label = "$providerId / ${model.name.ifEmpty { model.id }}"
                list.a(key to label)
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
                    Icon(Icons.Filled.ArrowDropnext, contentDescription = null)
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
                        contentPaing = ExposedDropdownMenuDefaults.ItemContentPaing
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
