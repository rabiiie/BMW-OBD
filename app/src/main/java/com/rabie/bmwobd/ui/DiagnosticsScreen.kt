package com.rabie.bmwobd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rabie.bmwobd.DiagnosticsState
import com.rabie.bmwobd.obd.DiagnosticsReport
import com.rabie.bmwobd.obd.Dtcs
import com.rabie.bmwobd.obd.Pids

@Composable
fun DiagnosticsScreen(
    state: DiagnosticsState,
    connected: Boolean,
    vehicleTerms: String,
    onRead: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmClear by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Label("Averías", color = Bmw.Text)

        if (!connected) {
            Text("Conéctate al coche en la pestaña Panel para leer las averías.", color = Bmw.TextDim)
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onRead, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Leer averías") }
            OutlinedButton(
                onClick = { confirmClear = true },
                enabled = !state.busy && state.report != null,
                modifier = Modifier.weight(1f),
            ) { Text("Borrar", color = if (state.report != null) Bmw.MRed else Bmw.TextDim) }
        }

        if (state.busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Preguntando al coche…", color = Bmw.Text)
            }
        }
        state.message?.let { Text(it, color = Bmw.Amber, fontSize = 14.sp) }

        val report = state.report
        if (report == null) {
            Text(
                "Solo salen las averías que la centralita del motor publica por el OBD estándar, que son " +
                    "las relacionadas con emisiones. Las propias de BMW no se ven con este adaptador.",
                color = Bmw.TextDim,
                fontSize = 14.sp,
            )
        } else {
            Report(report, vehicleTerms)
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("¿Borrar las averías?") },
            text = {
                Text(
                    "Se borran los códigos, la foto de la avería y los contadores, y se apaga el testigo. " +
                        "Si la causa sigue ahí, la avería volverá. Hazlo con el contacto puesto y el motor parado.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text("Borrar", color = Bmw.MRed) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Report(report: DiagnosticsReport, vehicleTerms: String) {
    Card {
        Label("Testigo de avería")
        val mil = when (report.milOn) {
            true -> "Encendido"
            false -> "Apagado"
            null -> "El coche no contesta"
        }
        Text(mil, color = if (report.milOn == true) Bmw.MRed else Bmw.Text, fontSize = 22.sp)
        report.dtcCount?.let { Text("La centralita cuenta $it averías guardadas.", color = Bmw.TextDim, fontSize = 13.sp) }
    }

    CodeList("Guardadas", report.stored, Bmw.MRed, vehicleTerms)
    CodeList("Pendientes (aún sin confirmar)", report.pending, Bmw.Amber, vehicleTerms)
    CodeList("Permanentes (no se borran a mano)", report.permanent, Bmw.MBlueLight, vehicleTerms)
    if (listOfNotNull(report.stored, report.pending, report.permanent).any { it.isNotEmpty() }) {
        Text("Toca un código para buscarlo en internet con los datos de este coche.", color = Bmw.TextDim, fontSize = 12.sp)
    }

    if (report.freezeDtc != null) {
        Card {
            Label("Foto de la avería ${report.freezeDtc}")
            Text("Cómo iba el motor cuando saltó.", color = Bmw.TextDim, fontSize = 13.sp)
            for ((id, value) in report.freeze) {
                val def = Pids.byId[id] ?: continue
                Row {
                    Text(def.name, color = Bmw.Text, modifier = Modifier.weight(1f))
                    Text(formatValue(value, def.decimals) + " " + def.unit, color = Bmw.Text)
                }
            }
        }
    }

    if (report.vin != null || report.calibration != null) {
        Card {
            Label("Coche")
            report.vin?.let { Text("Bastidor  $it", color = Bmw.Text, fontFamily = FontFamily.Monospace, fontSize = 14.sp) }
            report.calibration?.let {
                Text("Software  $it", color = Bmw.Text, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun CodeList(title: String, codes: List<String>?, color: Color, vehicleTerms: String) {
    val context = LocalContext.current
    Card {
        Label(title)
        when {
            codes == null -> Text("El coche no contesta a esta consulta.", color = Bmw.TextDim)
            codes.isEmpty() -> Text("Ninguna.", color = Bmw.Text)
            else -> for (code in codes) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { searchWeb(context, "$code $vehicleTerms") },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(code, color = color, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                    Text(Dtcs.describe(code), color = Bmw.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Bmw.Surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}
