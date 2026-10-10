package com.rabie.bmwobd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rabie.bmwobd.scan.ScanProgress
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** El dialogo crudo con el adaptador y una consola para mandarle comandos a mano. */
@Composable
fun LogScreen(
    lines: List<String>,
    canSend: Boolean,
    onSend: (String) -> Unit,
    scan: ScanProgress,
    canScan: Boolean,
    onToggleScan: () -> Unit,
    onResetScan: () -> Unit,
    scanFile: () -> File?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var command by rememberSaveable { mutableStateOf("") }
    val send = {
        onSend(command)
        command = ""
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Registro", Modifier.weight(1f), color = Bmw.Text)
            OutlinedButton(onClick = onToggleScan, enabled = canScan, modifier = Modifier.padding(end = 8.dp)) {
                Text(if (scan.active) "Parar escaneo" else "Escaneo")
            }
            OutlinedButton(
                onClick = { shareLog(context, lines) },
                enabled = lines.isNotEmpty(),
            ) { Text("Compartir") }
        }
        ScanRow(scan, canScan, onResetScan, scanFile)

        if (lines.isEmpty()) {
            Text(
                "Aquí sale lo que se envía al adaptador (>>) y lo que contesta (<<).",
                color = Bmw.TextDim,
                modifier = Modifier.weight(1f),
            )
        } else {
            LogView(lines, Modifier.weight(1f))
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                enabled = canSend,
                singleLine = true,
                placeholder = { Text("Comando: ATRV, 0105…") },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = send, enabled = canSend && command.isNotBlank()) { Text("Enviar") }
        }
    }
}

/**
 * Por donde va el barrido de todo lo que contesta la centralita y el boton para sacar el fichero.
 * La primera pasada pregunta por todo; las siguientes repiten lo que contesto.
 */
@Composable
private fun ScanRow(scan: ScanProgress, canScan: Boolean, onReset: () -> Unit, scanFile: () -> File?) {
    val context = LocalContext.current
    val file = scanFile()
    if (!scan.active && file == null) return
    val status = when {
        scan.finished -> "Escaneo terminado: ${scan.answering} consultas con dato."
        !scan.active -> "Escaneo parado. Hay resultados guardados."
        scan.pass == 1 -> "Escaneo, búsqueda: ${scan.done} de ${scan.total} · con dato ${scan.answering}"
        scan.pass == 2 -> "Escaneo, repaso: ${scan.done} de ${scan.total} · con dato ${scan.answering}"
        else -> "Escaneo, repetición ${scan.pass - 2} de 15: ${scan.done} de ${scan.total}"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(status, color = Bmw.TextDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (!scan.active && canScan && file != null) {
            OutlinedButton(onClick = onReset) { Text("Borrar") }
        }
        OutlinedButton(
            onClick = { file?.let { shareFile(context, it, "text/csv", scanExportName()) } },
            enabled = file != null,
        ) { Text("Enviar") }
    }
}

/** Nombre con el que sale el fichero del barrido: con la fecha y sin el bastidor. */
private fun scanExportName(): String =
    "escaneo_bmwobd_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".csv"

@Composable
fun LogView(lines: List<String>, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier.fillMaxWidth(), reverseLayout = true) {
        items(lines.asReversed()) { line ->
            Text(
                line,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = when {
                    line.startsWith("!!") -> Bmw.MRed
                    line.startsWith(">>") -> Bmw.Accent
                    else -> Bmw.Text
                },
            )
        }
    }
}
