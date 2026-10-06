package com.rabie.bmwobd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rabie.bmwobd.vehicle.Vehicle

/** Lo que el coche no dice de si mismo: como llamarlo, su cilindrada y, si se detecto mal, el combustible. */
@Composable
fun VehicleDialog(vehicle: Vehicle, onSave: (Vehicle) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(vehicle.name) }
    var liters by remember { mutableStateOf(vehicle.displacementLiters?.toString().orEmpty()) }
    var diesel by remember { mutableStateOf(vehicle.diesel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Perfil del coche") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Bastidor: " + (vehicle.vin ?: "no leído"), fontSize = 13.sp)
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Nombre") })
                OutlinedTextField(
                    value = liters,
                    onValueChange = { liters = it },
                    singleLine = true,
                    label = { Text("Cilindrada en litros (2.0)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Diésel", modifier = Modifier.weight(1f))
                    Switch(checked = diesel, onCheckedChange = { diesel = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    vehicle.copy(
                        name = name.trim().ifEmpty { vehicle.name },
                        diesel = diesel,
                        displacementLiters = liters.replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.5..9.0 },
                    ),
                )
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
