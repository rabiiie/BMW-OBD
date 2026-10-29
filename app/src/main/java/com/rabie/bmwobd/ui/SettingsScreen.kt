package com.rabie.bmwobd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rabie.bmwobd.settings.Accent
import com.rabie.bmwobd.settings.AppSettings

@Composable
fun SettingsScreen(
    settings: AppSettings,
    bubbleEnabled: Boolean,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onToggleBubble: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Label("Ajustes", color = Bmw.Text)

        Label("Aspecto", Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (accent in Accent.entries) {
                AccentChoice(accent, selected = accent == settings.accent, modifier = Modifier.weight(1f)) {
                    onChange { it.copy(accent = accent) }
                }
            }
        }

        Label("Conexión", Modifier.padding(top = 6.dp))
        Toggle(
            title = "Conectar sola",
            detail = "Con la app abierta, conecta sola con el último adaptador usado, y lo vuelve a intentar si se corta.",
            checked = settings.autoConnect,
        ) { value -> onChange { it.copy(autoConnect = value) } }

        Label("Gasóleo", Modifier.padding(top = 6.dp))
        FuelPrice(settings.fuelPrice) { value -> onChange { it.copy(fuelPrice = value) } }

        Label("Avisos", Modifier.padding(top = 6.dp))
        Toggle(
            title = "Sonido de aviso",
            detail = "Un pitido cuando aparece un aviso en rojo.",
            checked = settings.sound,
        ) { value -> onChange { it.copy(sound = value) } }
        if (settings.ignoredFaults.isEmpty()) {
            Text("Ninguna avería ignorada. Un aviso de avería nueva se puede ignorar desde su tarjeta.", color = Bmw.TextDim, fontSize = 13.sp)
        } else {
            Text("Averías que no avisan en marcha:", color = Bmw.Text, fontSize = 14.sp)
            for (code in settings.ignoredFaults.sorted()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(code + (com.rabie.bmwobd.obd.BmwFaultTexts.of(code.toIntOrNull(16) ?: -1)?.first?.let { " · $it" } ?: ""), color = Bmw.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    androidx.compose.material3.TextButton(onClick = { onChange { it.copy(ignoredFaults = it.ignoredFaults - code) } }) {
                        Text("Volver a avisar", color = Bmw.Accent, fontSize = 13.sp)
                    }
                }
            }
        }

        Label("Pantalla", Modifier.padding(top = 6.dp))
        Toggle(
            title = "Mantener la pantalla encendida",
            detail = "Mientras el panel está abierto y hay conexión.",
            checked = settings.keepScreenOn,
        ) { value -> onChange { it.copy(keepScreenOn = value) } }
        Toggle(
            title = "Burbuja sobre el navegador",
            detail = "Turbo, temperaturas y avisos encima de otras apps. Pide un permiso de Android la primera vez.",
            checked = bubbleEnabled,
        ) { onToggleBubble() }

        Toggle(
            title = "Desactivar el arranque y parada al conectar",
            detail = "Solo BMW N47. Al conectar, la app le pide a la centralita que no pare el motor en los semáforos " +
                "hasta quitar el contacto (la orden de BMW 2E 5F 8E 07). Al apagarlo, lo devuelve a lo normal.",
            checked = settings.disableMsa,
        ) { value -> onChange { it.copy(disableMsa = value) } }

        Label("Avanzado", Modifier.padding(top = 6.dp))
        Toggle(
            title = "Consola en modo experto",
            detail = "Deja enviar al coche cualquier comando, también los que borran o escriben. " +
                "Apagado, la consola solo acepta consultas.",
            checked = settings.expertConsole,
        ) { value -> onChange { it.copy(expertConsole = value) } }
    }
}

/** El precio del litro. Vacio, no se hace la cuenta en euros. Acepta coma o punto. */
@Composable
private fun FuelPrice(price: Double?, onChange: (Double?) -> Unit) {
    var text by remember { mutableStateOf(price?.toString()?.replace('.', ',') ?: "") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Bmw.Surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Precio del litro", color = Bmw.Text, fontSize = 16.sp)
            Text("Para ver en euros lo que cuesta cada trayecto. Cámbialo al repostar.", color = Bmw.TextDim, fontSize = 13.sp)
        }
        OutlinedTextField(
            value = text,
            onValueChange = { typed ->
                text = typed.filter { it.isDigit() || it == ',' || it == '.' }.take(6)
                onChange(text.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 })
            },
            singleLine = true,
            suffix = { Text("€") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(110.dp),
        )
    }
}

@Composable
private fun Toggle(title: String, detail: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Bmw.Surface)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Bmw.Text, fontSize = 16.sp)
            Text(detail, color = Bmw.TextDim, fontSize = 13.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AccentChoice(accent: Accent, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Bmw.SurfaceHigh else Bmw.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(Color(accent.argb)))
        Text(accent.title, color = if (selected) Bmw.Text else Bmw.TextDim, fontSize = 15.sp)
    }
}
