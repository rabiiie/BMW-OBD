package com.rabie.bmwobd.ui

import androidx.compose.foundation.background
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
import com.rabie.bmwobd.obd.Actuator
import com.rabie.bmwobd.obd.BmwService
import com.rabie.bmwobd.obd.Need
import com.rabie.bmwobd.obd.ServiceRoutine
import com.rabie.bmwobd.obd.TestOutcome
import com.rabie.bmwobd.tests.TestRecord
import com.rabie.bmwobd.tests.TestResultStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Las pruebas de piezas y los servicios que escriben en la centralita, cada uno en su tarjeta con
 * su boton y como acabo la ultima vez. La app sabe si la centralita acepto y si una lectura de
 * control se movio; si la pieza se movio de verdad lo dice quien la oye, con Si o No.
 */
@Composable
fun TestsScreen(
    state: DiagnosticsState,
    connected: Boolean,
    bmw: Boolean,
    records: Map<String, TestRecord>,
    onTestActuator: (Actuator) -> Unit,
    onRunRoutine: (ServiceRoutine) -> Unit,
    onHeard: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmActuator by remember { mutableStateOf<Actuator?>(null) }
    var confirmRoutine by remember { mutableStateOf<ServiceRoutine?>(null) }
    val canRun = connected && bmw && !state.busy

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Label("Pruebas", color = Bmw.Text)
        when {
            !connected -> Text("Conéctate al coche en la pestaña Panel para hacer pruebas. Abajo está cómo acabó cada una la última vez.", color = Bmw.TextDim, fontSize = 14.sp)
            !bmw -> Text("Las pruebas son de la centralita del N47: este coche no la tiene reconocida.", color = Bmw.TextDim, fontSize = 14.sp)
        }
        state.message?.takeIf { state.runningTest == null }?.let { Text(it, color = Bmw.Accent, fontSize = 14.sp) }

        Label("Pruebas de piezas", color = Bmw.Text)
        for (actuator in listOf(BmwService.FAN) + BmwService.ACTUATORS) {
            val key = TestResultStore.actuatorKey(actuator.lid)
            TestCard(
                title = actuator.name,
                description = actuator.description,
                hint = "ISTA la hace con el contacto puesto y el motor apagado.",
                record = records[key],
                running = state.runningTest == key,
                enabled = canRun,
                askHeard = true,
                onRun = { confirmActuator = actuator },
                onHeard = { onHeard(key, it) },
            )
        }

        Label("Servicios que escriben en la centralita", color = Bmw.MRed)
        for (routine in BmwService.ROUTINES) {
            val key = TestResultStore.routineKey(routine.name)
            TestCard(
                title = routine.name,
                description = routine.description,
                hint = if (routine.need == Need.IDLE) "ISTA lo hace al ralentí, parado y con el motor caliente." else "ISTA lo hace con el contacto puesto y el motor apagado.",
                record = records[key],
                running = state.runningTest == key,
                enabled = canRun,
                askHeard = false,
                onRun = { confirmRoutine = routine },
                onHeard = { onHeard(key, it) },
            )
        }
    }

    confirmActuator?.let { actuator ->
        AlertDialog(
            onDismissRequest = { confirmActuator = null },
            title = { Text("¿Probar: ${actuator.name}?") },
            text = {
                Text(
                    "La app pide a la centralita " + actuator.values.joinToString(" y luego ") { actuator.valueText(it) } +
                        " durante ${"%.1f".format(Locale.US, actuator.holdMs / 1000.0)} s cada vez, con la orden de la tabla STELLER, " +
                        "y le devuelve el control. Mantente lejos de lo que se mueve. Si algo se quedara moviéndose, quita el contacto.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmActuator = null
                    onTestActuator(actuator)
                }) { Text("Probar", color = Bmw.Accent) }
            },
            dismissButton = { TextButton(onClick = { confirmActuator = null }) { Text("Cancelar") } },
        )
    }

    confirmRoutine?.let { routine ->
        AlertDialog(
            onDismissRequest = { confirmRoutine = null },
            title = { Text("¿${routine.name}?") },
            text = {
                Text(
                    routine.description + " Telegramas: " + (routine.start + routine.finish).joinToString(", ") +
                        ", los mismos que manda ISTA. Escribe en la centralita.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRoutine = null
                    onRunRoutine(routine)
                }) { Text("Hacerlo", color = Bmw.MRed) }
            },
            dismissButton = { TextButton(onClick = { confirmRoutine = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun TestCard(
    title: String,
    description: String,
    hint: String,
    record: TestRecord?,
    running: Boolean,
    enabled: Boolean,
    askHeard: Boolean,
    onRun: () -> Unit,
    onHeard: (Boolean) -> Unit,
) {
    var showSteps by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Bmw.Surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Bmw.Text, fontSize = 16.sp, modifier = Modifier.weight(1f))
            when {
                running -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                record != null -> Text(record.outcome.title, color = outcomeColor(record.outcome), fontSize = 14.sp)
                else -> Text("Sin hacer", color = Bmw.TextDim, fontSize = 14.sp)
            }
        }
        if (description.isNotEmpty()) Text(description, color = Bmw.Text, fontSize = 14.sp)
        Text(hint, color = Bmw.TextDim, fontSize = 12.sp)
        if (running) Text("En curso…", color = Bmw.Accent, fontSize = 14.sp)
        record?.let { last ->
            Text(
                "Última vez: " + SimpleDateFormat("d MMM yyyy, HH:mm", Locale("es", "ES")).format(Date(last.atMillis)),
                color = Bmw.TextDim,
                fontSize = 12.sp,
            )
            Text(last.message, color = Bmw.Text, fontSize = 14.sp)
            if (askHeard) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("¿Se ha oído o visto?", color = Bmw.TextDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onHeard(true) }) { Text("Sí", color = if (last.heard == true) Bmw.Accent else Bmw.TextDim) }
                    TextButton(onClick = { onHeard(false) }) { Text("No", color = if (last.heard == false) Bmw.MRed else Bmw.TextDim) }
                }
            }
            TextButton(onClick = { showSteps = !showSteps }) {
                Text(if (showSteps) "Ocultar los pasos" else "Ver los pasos (${last.steps.size})", color = Bmw.TextDim, fontSize = 13.sp)
            }
            if (showSteps) {
                for (step in last.steps) Text(step, color = Bmw.TextDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
        OutlinedButton(onClick = onRun, enabled = enabled && !running, modifier = Modifier.fillMaxWidth()) {
            Text(if (record == null) "Probar" else "Probar otra vez", color = if (enabled) Bmw.Accent else Bmw.TextDim)
        }
    }
}

private fun outcomeColor(outcome: TestOutcome): Color = when (outcome) {
    TestOutcome.OK -> Color(0xFF4CAF50)
    TestOutcome.REFUSED -> Bmw.MRed
    TestOutcome.DOUBTFUL -> Bmw.Amber
    TestOutcome.SILENT -> Bmw.TextDim
}
