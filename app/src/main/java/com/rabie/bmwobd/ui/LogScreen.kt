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

/** El dialogo crudo con el adaptador y una consola para mandarle comandos a mano. */
@Composable
fun LogScreen(
    lines: List<String>,
    canSend: Boolean,
    onSend: (String) -> Unit,
    onProbe: () -> Unit,
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
            OutlinedButton(onClick = onProbe, enabled = canSend, modifier = Modifier.padding(end = 8.dp)) {
                Text("Sondeo")
            }
            OutlinedButton(
                onClick = { shareText(context, "Registro BMW OBD", lines.joinToString("\n")) },
                enabled = lines.isNotEmpty(),
            ) { Text("Compartir") }
        }

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
                    line.startsWith(">>") -> Bmw.Amber
                    else -> Bmw.Text
                },
            )
        }
    }
}
