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
import com.rabie.bmwobd.obd.BmwBattery
import com.rabie.bmwobd.obd.BmwFault
import com.rabie.bmwobd.obd.BmwIma
import com.rabie.bmwobd.obd.DiagnosticsReport
import com.rabie.bmwobd.obd.IstaRegen
import com.rabie.bmwobd.obd.ManipulationReport
import com.rabie.bmwobd.obd.ServiceRead
import com.rabie.bmwobd.obd.FaultSnapshot
import com.rabie.bmwobd.obd.Dtcs
import com.rabie.bmwobd.obd.FilterSensorCheck
import com.rabie.bmwobd.obd.Mode06
import com.rabie.bmwobd.obd.MonitorTest
import com.rabie.bmwobd.obd.Readiness
import com.rabie.bmwobd.obd.RecordCheck
import com.rabie.bmwobd.obd.CheckLine
import com.rabie.bmwobd.obd.Pids
import com.rabie.bmwobd.obd.PurchaseCheck

@Composable
fun DiagnosticsScreen(
    state: DiagnosticsState,
    connected: Boolean,
    vehicleTerms: String,
    onRead: () -> Unit,
    onReadTests: () -> Unit,
    onClear: () -> Unit,
    onClearFilter: () -> Unit = {},
    onClearFilterAll: () -> Unit = {},
    onClearBmwAll: () -> Unit = {},
    onReadService: () -> Unit = {},
    bmwService: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var confirmClear by remember { mutableStateOf(false) }
    var confirmFilter by remember { mutableStateOf(false) }
    var confirmFilterAll by remember { mutableStateOf(false) }
    var confirmBmwAll by remember { mutableStateOf(false) }
    // Lo del filtro y la contrapresion que trajo la ultima lectura, en averias o en avisos.
    val filterPresent = state.report?.let { r -> (r.bmwFaults.orEmpty() + r.bmwInfo.orEmpty()).map { it.code }.distinct().filter { it in BmwFault.FILTER_ALL_CODES } }.orEmpty()

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
            // Sin conexion se enseña la ultima lectura guardada en el movil, sin botones.
            state.report?.let { saved ->
                LastReadLine(state)
                Report(saved, vehicleTerms)
                state.manipulation?.let { ManipulationCard(it) }
            }
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

        // Los borrados solo se dejan con el detalle de lo que se borra ya guardado en el registro.
        val filterFaults = state.report?.bmwFaults.orEmpty().map { it.code }.filter { it in BmwFault.FILTER_CODES }
        if (filterFaults.isNotEmpty()) {
            ClearButton("Borrar solo 480A/481A (filtro)", state.busy) { confirmFilter = true }
        }
        if (filterPresent.isNotEmpty()) {
            ClearButton("Borrar todo lo del filtro en la memoria de BMW", state.busy) { confirmFilterAll = true }
        }
        val allCodes = state.report?.let(RecordCheck::codes).orEmpty()
        if (allCodes.isNotEmpty()) {
            ClearButton("Borrar toda la memoria de BMW", state.busy) { confirmBmwAll = true }
        }

        if (state.busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Preguntando al coche…", color = Bmw.Text)
            }
        }
        state.message?.let { Text(it, color = Bmw.Accent, fontSize = 14.sp) }

        RecordCheck.lines(state.report, state.service).takeIf { it.isNotEmpty() }?.let { RecordCheckCard(it) }

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
            LastReadLine(state)
            Report(report, vehicleTerms)
        }
        state.manipulation?.let { ManipulationCard(it) }
        state.tests?.takeIf { it.isNotEmpty() }?.let { Tests(it) }

        if (bmwService) {
            Label("Servicio (como ISTA)", color = Bmw.Text)
            OutlinedButton(onClick = onReadService, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Leer datos de servicio (solo lectura)")
            }
            Text("Las pruebas de piezas y los servicios están en la pestaña Pruebas.", color = Bmw.TextDim, fontSize = 13.sp)
            state.istaRegen?.let { IstaRegenCard(it) }
            state.service?.let { ServiceCard(it) }
        }
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

    if (confirmBmwAll) {
        AlertDialog(
            onDismissRequest = { confirmBmwAll = false },
            title = { Text("¿Borrar toda la memoria de BMW?") },
            text = {
                Text(
                    "Se manda la orden de BMW que borra todas las averías de su memoria (14 FFFF). Si después quedan avisos " +
                        "en la memoria de información, se borran uno a uno. El historial no se toca. Con el coche parado, " +
                        "mejor con el contacto puesto y el motor parado. Lee antes las averías para que su detalle quede en el " +
                        "registro. Las que estén presentes (como la del sensor de aceite) volverán a salir enseguida.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmBmwAll = false
                    onClearBmwAll()
                }) { Text("Borrar todo", color = Bmw.MRed) }
            },
            dismissButton = { TextButton(onClick = { confirmBmwAll = false }) { Text("Cancelar") } },
        )
    }

    if (confirmFilterAll) {
        AlertDialog(
            onDismissRequest = { confirmFilterAll = false },
            title = { Text("¿Borrar todo lo del filtro?") },
            text = {
                Text(
                    "Se borran de la memoria de averías y de la de información de BMW: " +
                        filterPresent.joinToString(", ") { "%04X".format(it) } +
                        ". Lo demás (aceite, calentadores…) se conserva. Con el coche parado, mejor con el contacto puesto " +
                        "y el motor parado. Lee antes las averías para que su detalle quede en el registro. No se sabe si " +
                        "la centralita acepta borrar los avisos de la memoria de información ni si cambia algo: si la causa " +
                        "sigue, los vuelve a apuntar.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmFilterAll = false
                    onClearFilterAll()
                }) { Text("Borrar", color = Bmw.MRed) }
            },
            dismissButton = { TextButton(onClick = { confirmFilterAll = false }) { Text("Cancelar") } },
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
    PurchaseCard(report)

    CodeList("Guardadas", report.stored, Bmw.MRed, vehicleTerms)
    CodeList("Pendientes (aún sin confirmar)", report.pending, Bmw.Accent, vehicleTerms)
    CodeList("Permanentes (no se borran a mano)", report.permanent, Bmw.MBlueLight, vehicleTerms)
    if (listOfNotNull(report.stored, report.pending, report.permanent).any { it.isNotEmpty() }) {
        Text("Toca un código para buscarlo en internet con los datos de este coche.", color = Bmw.TextDim, fontSize = 12.sp)
    }
    report.bmwFaults?.let { BmwFaultsCard(it, report.bmwFaultKm, report.bmwFaultShots, vehicleTerms) }
    report.bmwInfo?.let { BmwInfoCard(it, report.bmwFaultKm, report.bmwFaultShots) }
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

    if (report.vin != null || report.calibration != null || report.cvn != null) {
        Card {
            Label("Coche")
            report.vin?.let { Text("Bastidor  $it", color = Bmw.Text, fontFamily = FontFamily.Monospace, fontSize = 14.sp) }
            report.calibration?.let {
                Text("Software  $it", color = Bmw.Text, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            }
            report.cvn?.let { cvn ->
                Text("CVN  ${cvn.joinToString(" ")}", color = Bmw.Text, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                Text(
                    "Suma de comprobación del programa del motor. Si alguien cambia el programa, cambia. No hay una lista " +
                        "pública de las de serie: sirve para apuntarla y ver si un día es otra.",
                    color = Bmw.TextDim,
                    fontSize = 12.sp,
                )
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

/** Un boton de borrar. */
@Composable
private fun ClearButton(label: String, busy: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Bmw.MRed)
    }
}

/** Que tiene el registro de la ultima lectura y que falta. */
@Composable
private fun RecordCheckCard(lines: List<CheckLine>) {
    Card {
        Label("Comprobación del registro")
        val complete = lines.all { it.ok }
        Text(
            if (complete) "Está todo: puedes pasar el registro." else "Falta algo: vuelve a leer lo que sale en rojo.",
            color = if (complete) Bmw.Text else Bmw.MRed,
            fontSize = 13.sp,
        )
        for (line in lines) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (line.ok) "✓" else "✗", color = if (line.ok) Bmw.Accent else Bmw.MRed, fontSize = 15.sp)
                Text(line.label, color = Bmw.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(line.detail, color = if (line.ok) Bmw.TextDim else Bmw.MRed, fontSize = 13.sp)
            }
        }
    }
}

/** La memoria de informacion de BMW: lo que la centralita apunta sin encender ningun testigo. */
@Composable
private fun BmwInfoCard(info: List<BmwFault>, kms: Map<Int, Int>, shots: Map<Int, List<FaultSnapshot>>) {
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
                kms[entry.code]?.let { Text("Apuntado a los ${thousands(it)} km", color = Bmw.TextDim, fontSize = 13.sp) }
            }
            shots[entry.code]?.let { FaultShots(it) }
        }
    }
}

/** El historial de averias de BMW: las ultimas que ha habido, aunque ya se hayan borrado. */
@Composable
private fun BmwHistoryCard(entries: List<BmwFault>) {
    Card {
        Label("Historial de averías de BMW")
        if (entries.isEmpty()) {
            Text("Vacío.", color = Bmw.Text)
            return@Card
        }
        Text(
            "Las últimas averías que ha apuntado la centralita, aunque ya no estén en la memoria, " +
                "en el orden en que las da. Sin fecha ni km.",
            color = Bmw.TextDim,
            fontSize = 13.sp,
        )
        for ((code, times) in entries.groupingBy { it.code }.eachCount()) {
            val fault = BmwFault(code, 0)
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(fault.label, color = Bmw.Accent, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                Text(fault.text ?: "Sin descripción en la app", color = Bmw.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (times > 1) Text("$times veces", color = Bmw.Text, fontSize = 14.sp)
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

/** Lo que mirar al comprar un coche, de cualquier marca: borrados recientes, bastidor, kilometros y filtro. */
@Composable
private fun PurchaseCard(report: DiagnosticsReport) {
    Card {
        Label("Revisión de compra")
        for (line in PurchaseCheck.lines(report)) {
            Text(line.text, color = if (line.warn) Bmw.Accent else Bmw.Text, fontSize = 14.sp, modifier = Modifier.padding(vertical = 2.dp))
        }
        Text(
            "Sale del OBD estándar y vale para cualquier coche. Que aquí no se vea nada raro no prueba que no se haya " +
                "tocado: una reprogramación de la centralita no deja rastro en estos datos.",
            color = Bmw.TextDim,
            fontSize = 12.sp,
        )
    }
}

/** Cuando se hizo la lectura que se enseña y de que coche: la guardada en el movil vale hasta la siguiente. */
@Composable
private fun LastReadLine(state: DiagnosticsState) {
    if (state.readAtMillis <= 0) return
    val date = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale("es", "ES")).format(java.util.Date(state.readAtMillis))
    Text(
        "Última lectura: $date" + (state.readVehicle?.let { " · $it" } ?: "") + ". Se sustituye al volver a leer.",
        color = Bmw.TextDim,
        fontSize = 13.sp,
    )
}

/** Lo que guarda la centralita del N47 para detectar manipulaciones, con el aviso de lo que no se sabe. */
@Composable
private fun ManipulationCard(manipulation: ManipulationReport) {
    Card {
        Label("Manipulación (centralita del motor)")
        for (line in manipulation.lines()) {
            Text(line.text, color = if (line.warn) Bmw.Accent else Bmw.Text, fontSize = 14.sp, modifier = Modifier.padding(vertical = 2.dp))
        }
        Text(
            "Lo calcula la propia centralita y lo guarda con los km de cada anotación. BMW no explica cómo lo calcula ni " +
                "desde qué valor lo da por manipulación: un valor distinto de 0 es para mirar, no una prueba.",
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
