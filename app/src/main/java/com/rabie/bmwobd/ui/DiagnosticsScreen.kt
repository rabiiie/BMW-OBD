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
import com.rabie.bmwobd.obd.Actuator
import com.rabie.bmwobd.obd.BmwBattery
import com.rabie.bmwobd.obd.BmwFault
import com.rabie.bmwobd.obd.BmwIma
import com.rabie.bmwobd.obd.BmwService
import com.rabie.bmwobd.obd.DiagnosticsReport
import com.rabie.bmwobd.obd.FanTest
import com.rabie.bmwobd.obd.IstaRegen
import com.rabie.bmwobd.obd.Need
import com.rabie.bmwobd.obd.ServiceRoutine
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.rabie.bmwobd.obd.ServiceRead
import com.rabie.bmwobd.obd.FaultSnapshot
import com.rabie.bmwobd.obd.Dtcs
import com.rabie.bmwobd.obd.FilterSensorCheck
import com.rabie.bmwobd.obd.Mode06
import com.rabie.bmwobd.obd.MonitorTest
import com.rabie.bmwobd.obd.Readiness
import com.rabie.bmwobd.obd.Pids

@Composable
fun DiagnosticsScreen(
    state: DiagnosticsState,
    connected: Boolean,
    vehicleTerms: String,
    onRead: () -> Unit,
    onReadTests: () -> Unit,
    onClear: () -> Unit,
    onClearFilter: () -> Unit = {},
    onReadService: () -> Unit = {},
    onTestActuator: (Actuator) -> Unit = {},
    onRunRoutine: (ServiceRoutine) -> Unit = {},
    servicePin: String? = null,
    onSetServicePin: (String) -> Unit = {},
    bmwService: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var confirmClear by remember { mutableStateOf(false) }
    var confirmFilter by remember { mutableStateOf(false) }
    var confirmActuator by remember { mutableStateOf<Actuator?>(null) }
    var confirmRoutine by remember { mutableStateOf<ServiceRoutine?>(null) }
    // Los servicios que escriben y las pruebas de piezas se abren con el PIN y se cierran al salir de la pantalla.
    var serviceOpen by remember { mutableStateOf(false) }
    var askPin by remember { mutableStateOf(false) }

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

        OutlinedButton(onClick = onReadTests, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Leer pruebas internas del coche")
        }

        // Solo si la ultima lectura trajo 480A o 481A.
        if (state.report?.bmwFaults.orEmpty().any { it.code in BmwFault.FILTER_CODES }) {
            OutlinedButton(onClick = { confirmFilter = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Borrar solo 480A/481A (filtro)", color = Bmw.MRed)
            }
        }

        if (state.busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Preguntando al coche…", color = Bmw.Text)
            }
        }
        state.message?.let { Text(it, color = Bmw.Accent, fontSize = 14.sp) }

        state.car?.battery?.let { BatteryCard(it) }
        state.car?.ima?.let { ImaCard(it) }
        state.car?.filterSensor?.let { FilterSensorCard(it) }

        val report = state.report
        if (report == null) {
            Text(
                "Salen las averías que la centralita del motor publica por el OBD estándar, que son las " +
                    "relacionadas con emisiones, y en un BMW diésel también las de su memoria propia. " +
                    "Las de otras centralitas (frenos, airbag, cuadro) no se ven con este adaptador.",
                color = Bmw.TextDim,
                fontSize = 14.sp,
            )
        } else {
            Report(report, vehicleTerms)
        }
        state.tests?.takeIf { it.isNotEmpty() }?.let { Tests(it) }

        if (bmwService) {
            Label("Servicio (como ISTA)", color = Bmw.Text)
            OutlinedButton(onClick = onReadService, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Leer datos de servicio (solo lectura)")
            }
            ActuatorButton(BmwService.FAN, BmwService.FAN_TEST_READY, state.busy) { confirmActuator = it }
            if (!serviceOpen) {
                OutlinedButton(onClick = { askPin = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (servicePin == null) "Servicios y pruebas de piezas (crear PIN)" else "Servicios y pruebas de piezas (PIN)", color = Bmw.MRed)
                }
            } else {
                Label("Pruebas de piezas", color = Bmw.Text)
                for (actuator in BmwService.ACTUATORS) {
                    ActuatorButton(actuator, BmwService.ACTUATORS_READY, state.busy) { confirmActuator = it }
                }
                Label("Servicios que escriben en la centralita", color = Bmw.MRed)
                for (routine in BmwService.ROUTINES) {
                    OutlinedButton(onClick = { confirmRoutine = routine }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(routine.name + if (routine.need == Need.IDLE) " (al ralentí)" else " (motor apagado)", color = Bmw.MRed)
                    }
                }
                TextButton(onClick = { serviceOpen = false }) { Text("Cerrar servicios", color = Bmw.TextDim) }
            }
            state.fanTest?.let { FanTestCard(it) }
            state.istaRegen?.let { IstaRegenCard(it) }
            state.service?.let { ServiceCard(it) }
        }
    }

    if (askPin) {
        PinDialog(
            existing = servicePin,
            onDismiss = { askPin = false },
            onCreate = { pin ->
                onSetServicePin(pin)
                askPin = false
                serviceOpen = true
            },
            onOpen = {
                askPin = false
                serviceOpen = true
            },
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

    confirmActuator?.let { actuator ->
        AlertDialog(
            onDismissRequest = { confirmActuator = null },
            title = { Text("¿Probar: ${actuator.name}?") },
            text = {
                Text(
                    "Con el contacto puesto y el motor apagado. La app pide a la centralita " +
                        actuator.percents.joinToString(" y luego ") { "%.0f %%".format(it) } +
                        " durante ${actuator.holdMs / 1000} s cada uno, con la misma orden que manda ISTA, y le devuelve el control. " +
                        (if (actuator.feedback != null) "Lee su sensor de posición en cada paso. " else "No tiene sensor de posición: fíjate si se oye. ") +
                        "Mantente lejos de lo que se mueve. Si algo se quedara moviéndose, quita el contacto.",
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

    if (confirmFilter) {
        AlertDialog(
            onDismissRequest = { confirmFilter = false },
            title = { Text("¿Borrar solo 480A/481A?") },
            text = {
                Text(
                    "Se borran solo las dos averías del filtro; las demás y sus kilómetros se conservan. " +
                        "Con el coche parado, mejor con el contacto puesto y el motor parado. " +
                        "Sin ellas la centralita puede empezar una regeneración, pero si el filtro sigue cargado " +
                        "las vuelve a poner. BMW solo fuerza la regeneración con estas dos averías no presentes, " +
                        "para no quemar de golpe un filtro muy cargado: la centralita lo compensa limitando la temperatura.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmFilter = false
                    onClearFilter()
                }) { Text("Borrar 480A/481A", color = Bmw.MRed) }
            },
            dismissButton = { TextButton(onClick = { confirmFilter = false }) { Text("Cancelar") } },
        )
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

    report.readiness?.let { ReadinessCard(it, report) }

    CodeList("Guardadas", report.stored, Bmw.MRed, vehicleTerms)
    CodeList("Pendientes (aún sin confirmar)", report.pending, Bmw.Accent, vehicleTerms)
    CodeList("Permanentes (no se borran a mano)", report.permanent, Bmw.MBlueLight, vehicleTerms)
    if (listOfNotNull(report.stored, report.pending, report.permanent).any { it.isNotEmpty() }) {
        Text("Toca un código para buscarlo en internet con los datos de este coche.", color = Bmw.TextDim, fontSize = 12.sp)
    }
    report.bmwFaults?.let { BmwFaultsCard(it, report.bmwFaultKm, report.bmwFaultShots, vehicleTerms) }
    report.bmwInfo?.let { BmwInfoCard(it) }
    report.bmwHistory?.let { BmwHistoryCard(it) }

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

/**
 * Las averias de la memoria propia de la centralita de BMW, que el OBD estandar no enseña. Leerlas
 * no las borra; el boton de borrar solo borra las del OBD estandar.
 */
@Composable
private fun BmwFaultsCard(faults: List<BmwFault>, kms: Map<Int, Int>, shots: Map<Int, List<FaultSnapshot>>, vehicleTerms: String) {
    val context = LocalContext.current
    Card {
        Label("En la memoria propia de BMW")
        if (faults.isEmpty()) {
            Text("Ninguna.", color = Bmw.Text)
            return@Card
        }
        Text(
            "No salen por el OBD estándar ni encienden por fuerza el testigo. Toca una para buscarla.",
            color = Bmw.TextDim,
            fontSize = 13.sp,
        )
        for (fault in faults.sortedByDescending { it.active }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { searchWeb(context, "BMW avería ${fault.label} $vehicleTerms") }
                    .padding(vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(fault.label, color = if (fault.active) Bmw.MRed else Bmw.Accent, fontFamily = FontFamily.Monospace, fontSize = 18.sp)
                    Text(fault.text ?: "Sin descripción en la app", color = Bmw.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                }
                fault.kind?.let { Text(it, color = Bmw.Text, fontSize = 14.sp) }
                Text(
                    (if (fault.active) "Presente ahora" else "Guardada de antes; ahora no está presente") +
                        (kms[fault.code]?.let { " · apuntada a los ${thousands(it)} km" } ?: ""),
                    color = Bmw.TextDim,
                    fontSize = 13.sp,
                )
            }
            shots[fault.code]?.let { FaultShots(it) }
        }
    }
}

/** Las condiciones de la primera y la ultima vez, plegadas: son veinticuatro valores por foto. */
@Composable
private fun FaultShots(shots: List<FaultSnapshot>) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text(if (open) "Ocultar cómo estaba el motor" else "Ver cómo estaba el motor (${shots.size} fotos)", color = Bmw.Accent, fontSize = 13.sp)
    }
    if (!open) return
    shots.forEachIndexed { index, shot ->
        val title = when {
            shots.size == 1 -> "Al apuntarse"
            index == 0 -> "La primera vez"
            else -> "La última vez"
        }
        Text(title + (shot.km?.let { " · ${thousands(it)} km" } ?: ""), color = Bmw.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        for (value in shot.values) {
            Row {
                Text(value.name + if (value.translated) "" else " (texto de BMW)", color = Bmw.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(value.text, color = Bmw.Text, fontSize = 12.sp)
            }
        }
    }
}

/** La memoria de informacion de BMW: lo que la centralita apunta sin encender ningun testigo. */
@Composable
private fun BmwInfoCard(info: List<BmwFault>) {
    Card {
        Label("Memoria de información de BMW")
        if (info.isEmpty()) {
            Text("Nada apuntado.", color = Bmw.Text)
            return@Card
        }
        Text("Avisos que la centralita guarda sin encender ningún testigo.", color = Bmw.TextDim, fontSize = 13.sp)
        for (entry in info) {
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(entry.label, color = Bmw.Accent, fontFamily = FontFamily.Monospace, fontSize = 18.sp)
                    Text(entry.text ?: "Sin descripción en la app", color = Bmw.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                }
                entry.kind?.let { Text(it, color = Bmw.Text, fontSize = 14.sp) }
            }
        }
    }
}

/** El historial de averias de BMW: las ultimas que ha habido, aunque ya se hayan borrado. */
@Composable
private fun BmwHistoryCard(codes: List<Int>) {
    Card {
        Label("Historial de averías de BMW")
        if (codes.isEmpty()) {
            Text("Vacío.", color = Bmw.Text)
            return@Card
        }
        Text("Las últimas averías que ha apuntado la centralita, aunque ya no estén en la memoria. Sin fecha ni km.", color = Bmw.TextDim, fontSize = 13.sp)
        for (code in codes) {
            val fault = BmwFault(code, 0)
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(fault.label, color = Bmw.Accent, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                Text(fault.text ?: "Sin descripción en la app", color = Bmw.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** La bateria segun la gestion de energia de la centralita del motor. */
@Composable
private fun BatteryCard(battery: BmwBattery) {
    Card {
        Label("Batería")
        Text("Carga ahora: ${formatValue(battery.charge, 1)} %", color = Bmw.Text, fontSize = 20.sp)
        Text(
            "Días anteriores: " + battery.chargeDays.joinToString(" · ") { formatValue(it, 1) + " %" },
            color = Bmw.Text,
            fontSize = 14.sp,
        )
        Text("Por debajo de ${formatValue(battery.startLimit, 1)} % la centralita teme que no arranque.", color = Bmw.TextDim, fontSize = 13.sp)
        val change = battery.lastChange
        Text(
            if (change != null) {
                "Último cambio de batería registrado: a los ${thousands(change)} km" +
                    (battery.kmDays.firstOrNull()?.let { " (hace ${thousands(it - change)} km)" } ?: "") + "."
            } else {
                "No tiene registrado ningún cambio de batería."
            },
            color = Bmw.Text,
            fontSize = 14.sp,
        )
        Text("Solo cuenta si quien la cambió lo registró con la máquina.", color = Bmw.TextDim, fontSize = 12.sp)
        val ranges = listOf("bajo 0 °C", "0–20 °C", "20–40 °C", "40–60 °C", "más de 60 °C")
        Text(
            "Horas por temperatura: " + battery.hoursByTemperature.zip(ranges).joinToString(" · ") { (hours, range) ->
                "$range ${thousands(hours.toInt())} h"
            },
            color = Bmw.TextDim,
            fontSize = 13.sp,
        )
    }
}

/** La prueba del sensor de presion del filtro del boletin de BMW: parado, debe marcar la presion del aire. */
@Composable
private fun FilterSensorCard(check: FilterSensorCheck) {
    Card {
        Label("Sensor de presión del filtro")
        val difference = check.difference
        if (check.engineRunning || difference == null) {
            Text("Se comprueba al conectar con el contacto puesto y el motor parado.", color = Bmw.TextDim, fontSize = 14.sp)
            return@Card
        }
        Text("Con el motor parado marca ${formatValue(check.sensor ?: 0.0, 0)} hPa; el aire está a ${formatValue(check.ambient ?: 0.0, 0)} hPa.", color = Bmw.Text, fontSize = 15.sp)
        Text("Diferencia: ${if (difference > 0) "+" else ""}${formatValue(difference, 0)} hPa.", color = Bmw.Text, fontSize = 20.sp)
        check.filtered?.let { Text("Su valor filtrado: ${formatValue(it, 0)} hPa.", color = Bmw.TextDim, fontSize = 13.sp) }
        Text(
            "Sin gas pasando, tienen que coincidir (prueba del boletín de BMW SI B18 03 10). BMW no da en el boletín la diferencia admitida.",
            color = Bmw.TextDim,
            fontSize = 12.sp,
        )
    }
}

/** Los codigos de calibracion grabados de cada inyector, para compararlos con los impresos. */
@Composable
private fun ImaCard(ima: BmwIma) {
    Card {
        Label("Códigos de los inyectores")
        val refusal = ima.refusal
        if (refusal != null) {
            Text("La centralita no los da por este camino: ${BmwIma.refusalText(refusal)}.", color = Bmw.Text, fontSize = 14.sp)
            return@Card
        }
        Text(
            if (ima.text) "Compáralos con los que lleva impresos cada inyector." else "En bruto: la app aún no sabe pasarlos al formato del inyector.",
            color = Bmw.TextDim,
            fontSize = 13.sp,
        )
        ima.codes.forEachIndexed { index, code ->
            Row {
                Text(if (ima.codes.size == 4) "Inyector ${index + 1}" else "Respuesta", color = Bmw.Text, modifier = Modifier.weight(1f))
                Text(code, color = Bmw.Text, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            }
        }
    }
}

/** El estado de la regeneracion con la regla de ISTA, para compararlo con lo que dice la app. */
@Composable
private fun IstaRegenCard(regen: IstaRegen) {
    Card {
        Label("Regeneración según ISTA")
        for ((name, on) in listOf("Pedida" to regen.requested, "En marcha" to regen.running, "Bloqueada" to regen.locked)) {
            Row {
                Text(name, color = Bmw.Text, modifier = Modifier.weight(1f))
                Text(if (on) "sí" else "no", color = if (on) Bmw.Accent else Bmw.TextDim)
            }
        }
        Text(
            "ISTA cuenta como pedida la petición 4, 5 o 6, y como en marcha cualquier modo del motor que acabe en 2, " +
                "también el de protección (0x00210002). Bloqueada es la medida 03EE distinta de 0.",
            color = Bmw.TextDim,
            fontSize = 12.sp,
        )
    }
}

/**
 * Crear el PIN de servicio (cuatro cifras, dos veces) o escribirlo para abrir. No es seguridad: es
 * para que no se pulse un servicio sin querer.
 */
@Composable
private fun PinDialog(existing: String?, onDismiss: () -> Unit, onCreate: (String) -> Unit, onOpen: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    fun digits(text: String) = text.filter { it.isDigit() }.take(PIN_LENGTH)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Crear el PIN de servicio" else "PIN de servicio") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (existing == null) {
                        "Abre las pruebas de piezas y los servicios que escriben en la centralita. Cuatro cifras."
                    } else {
                        "Escribe el PIN para abrir las pruebas de piezas y los servicios."
                    },
                    fontSize = 14.sp,
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = digits(it); wrong = false },
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                if (existing == null) {
                    OutlinedTextField(
                        value = again,
                        onValueChange = { again = digits(it); wrong = false },
                        label = { Text("Otra vez") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                }
                if (wrong) Text(if (existing == null) "No coinciden." else "No es el PIN.", color = Bmw.MRed, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton(
                enabled = pin.length == PIN_LENGTH,
                onClick = {
                    when {
                        existing == null && pin == again -> onCreate(pin)
                        existing != null && pin == existing -> onOpen()
                        else -> wrong = true
                    }
                },
            ) { Text(if (existing == null) "Crear" else "Abrir") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private const val PIN_LENGTH = 4

/** El boton de la prueba de una pieza; apagado mientras su prueba no este lista. */
@Composable
private fun ActuatorButton(actuator: Actuator, ready: Boolean, busy: Boolean, onClick: (Actuator) -> Unit) {
    OutlinedButton(onClick = { onClick(actuator) }, enabled = ready && !busy, modifier = Modifier.fillMaxWidth()) {
        Text("Probar: ${actuator.name} (motor apagado)", color = if (ready) Bmw.Accent else Bmw.TextDim)
    }
}

/** Cada paso de la prueba de una pieza: lo que se mando, lo que contesto y que significa. */
@Composable
private fun FanTestCard(test: FanTest) {
    Card {
        Label("Prueba: ${test.name}")
        for (step in test.steps) {
            Text("${step.label}: ${step.meaning}", color = Bmw.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            Text("${step.query} → ${step.response ?: "-"}", color = Bmw.TextDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}

/**
 * Las lecturas de servicio. Siempre va la respuesta tal cual; el valor y los bits solo cuando la
 * tabla de BMW dice como leerlos, con la nota de lo que no esta comprobado.
 */
@Composable
private fun ServiceCard(reads: List<ServiceRead>) {
    Card {
        Label("Datos de servicio")
        Text("Solo lectura, con los mismos telegramas que ISTA.", color = Bmw.TextDim, fontSize = 13.sp)
        for (read in reads) {
            Text(read.query.name, color = Bmw.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            read.valueText?.let { Text("Valor: $it", color = Bmw.Accent, fontSize = 14.sp) }
            for ((name, value) in read.fieldTexts) {
                Row {
                    Text(name, color = Bmw.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(value, color = Bmw.Text, fontSize = 12.sp)
                }
            }
            if (read.data != null && read.query.bits.isNotEmpty()) {
                val bits = read.activeBits
                Text("Bits: " + (if (bits.isEmpty()) "ninguno a 1" else bits.joinToString(", ")), color = Bmw.Text, fontSize = 13.sp)
            }
            Text(read.status, color = Bmw.TextDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            if (read.query.note.isNotEmpty()) Text(read.query.note, color = Bmw.TextDim, fontSize = 12.sp)
        }
    }
}

private fun thousands(value: Int) =String.format(java.util.Locale.US, "%,d", value).replace(',', '.')

/**
 * Las autocomprobaciones del coche. Es lo que lee una estacion de ITV por el conector: testigo,
 * averias y si estas comprobaciones han terminado.
 */
@Composable
private fun ReadinessCard(readiness: Readiness, report: DiagnosticsReport) {
    val pending = readiness.pending
    val clean = report.milOn == false && report.stored.isNullOrEmpty()
    Card {
        Label("Autocomprobaciones")
        val summary = when {
            pending.isEmpty() && clean -> "Todo completo y sin averías"
            pending.isEmpty() -> "Todas completas, pero hay averías"
            else -> "Faltan ${pending.size} de ${readiness.available.size} por completar"
        }
        Text(summary, color = if (pending.isEmpty() && clean) Bmw.Text else Bmw.Accent, fontSize = 20.sp)
        for (monitor in readiness.available) {
            Row {
                Text(monitor.name, color = Bmw.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(
                    if (monitor.complete) "Completa" else "Pendiente",
                    color = if (monitor.complete) Bmw.TextDim else Bmw.Accent,
                    fontSize = 14.sp,
                )
            }
        }
        Text(
            "Tras borrar averías o desconectar la batería vuelven a empezar y se completan con el uso normal, " +
                "a veces en varios días. La ITV lee por el conector el testigo, las averías y este estado.",
            color = Bmw.TextDim,
            fontSize = 12.sp,
        )
    }
}

/** Las pruebas internas, agrupadas por sistema, con lo medido y los limites del fabricante. */
@Composable
private fun Tests(tests: List<MonitorTest>) {
    Label("Pruebas internas", Modifier.padding(top = 6.dp))
    Text(
        "Lo que el propio coche midió en su última comprobación y los límites que le puso el fabricante. " +
            "\"Dentro\" o \"Fuera\" es fiable; la unidad de algunas pruebas puede no estar en la tabla.",
        color = Bmw.TextDim,
        fontSize = 12.sp,
    )
    for ((mid, group) in tests.groupBy { it.mid }) {
        Card {
            Label(Mode06.midName(mid))
            for (test in group) {
                fun n(value: Double) = formatValue(value, test.decimals)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            ("Prueba " + "%02X".format(test.tid) + ": ${n(test.value)} ${test.unit}").trim() +
                                if (test.unitKnown) "" else " (sin escala)",
                            color = Bmw.Text,
                            fontSize = 15.sp,
                        )
                        Text("Límites ${n(test.min)} a ${n(test.max)} ${test.unit}".trim(), color = Bmw.TextDim, fontSize = 12.sp)
                    }
                    Text(
                        if (test.passed) "Dentro" else "Fuera",
                        color = if (test.passed) Bmw.TextDim else Bmw.MRed,
                        fontSize = 14.sp,
                    )
                }
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
