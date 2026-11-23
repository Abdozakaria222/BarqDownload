@file:OptIn(ExperimentalMaterial3Api::class)

package com.abdo.barq.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abdo.barq.R
import com.abdo.barq.Sys
import com.abdo.barq.core.DownloadItem
import com.abdo.barq.core.FileTypes
import com.abdo.barq.core.Kind
import com.abdo.barq.core.Names
import com.abdo.barq.tr

@Composable
fun Ico(id: Int, desc: String? = null, modifier: Modifier = Modifier, tint: Color? = null) {
    if (tint != null) Icon(painterResource(id), desc, modifier, tint = tint) else Icon(painterResource(id), desc, modifier)
}

@Composable
fun IconBtn(id: Int, desc: String, enabled: Boolean = true, tint: Color? = null, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) { Ico(id, desc, tint = tint) }
}

fun iconFor(it: DownloadItem): Int {
    if (it.kind == Kind.TORRENT) return R.drawable.ic_hub
    if (it.kind == Kind.VIDEO) return if (it.vQuality == "mp3" || it.vQuality == "m4a") R.drawable.ic_music_note else R.drawable.ic_smart_display
    return iconForName(it.fileName)
}

fun iconForName(name: String): Int = when (FileTypes.group(Names.ext(name))) {
    1 -> R.drawable.ic_image
    2 -> R.drawable.ic_movie
    3 -> R.drawable.ic_music_note
    4 -> if (Names.ext(name) in FileTypes.PROGRAM) R.drawable.ic_android else R.drawable.ic_folder_zip
    5 -> R.drawable.ic_description
    0 -> R.drawable.ic_web
    else -> R.drawable.ic_insert_drive_file
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
    )
}

@Composable
fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun CheckRow(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(title, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun RadioRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(title, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun Field(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth(),
    singleLine: Boolean = true, number: Boolean = false, placeholder: String? = null, enabled: Boolean = true,
    minLines: Int = 1, trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine, enabled = enabled,
        minLines = if (singleLine) 1 else minLines,
        placeholder = if (placeholder != null) { { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) } } else null,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        trailingIcon = trailing,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

@Composable
fun IntField(label: String, value: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier.fillMaxWidth(), min: Int = 0, max: Int = Int.MAX_VALUE) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Field(label, text, { t ->
        text = t.filter { c -> c.isDigit() }.take(9)
        text.toIntOrNull()?.let { n -> onChange(n.coerceIn(min, max)) }
    }, modifier, number = true)
}

@Composable
fun <T> Dropdown(label: String, options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier.fillMaxWidth(), onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier.padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second ?: "",
            onValueChange = {}, readOnly = true, label = { Text(label) }, singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((v, t) in options) {
                DropdownMenuItem(text = { Text(t) }, onClick = { onSelect(v); open = false })
            }
        }
    }
}

@Composable
fun SliderRow(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value.toString(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.toFloat(), onValueChange = { onChange(it.toInt().coerceIn(range.first, range.last)) },
            valueRange = range.first.toFloat()..range.last.toFloat(), steps = (range.last - range.first - 1).coerceIn(0, 100),
        )
    }
}

/** A folder field with a button that opens the system folder picker. */
@Composable
fun FolderField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) Sys.treeUriToPath(uri)?.let(onChange)
    }
    Field(label, value, onChange, modifier, trailing = {
        IconButton(onClick = { runCatching { picker.launch(null) } }) { Ico(com.abdo.barq.R.drawable.ic_folder_open, tr("Browse…", "استعراض…")) }
    })
}

@Composable
fun KeyValue(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(120.dp))
        Text(v, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
fun SmallIcon(id: Int, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) = Ico(id, null, Modifier.size(16.dp), tint)
