package com.rabie.bmwobd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rabie.bmwobd.DeviceItem
import com.rabie.bmwobd.LiveState
import com.rabie.bmwobd.Phase

@Composable
fun ConnectScreen(
    title: String,
    state: LiveState,
    devices: List<DeviceItem>,
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    onConnect: (DeviceItem) -> Unit,
    onSimulate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connecting = state.phase == Phase.CONNECTING

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(title, color = Bmw.Text, fontSize = 34.sp, fontWeight = FontWeight.Light, letterSpacing = 2.sp, maxLines = 1)
        MStripe()
        Text(
            "Empareja el ELM327 en los ajustes de Bluetooth del móvil (PIN 1234 o 0000), " +
                "pon el contacto y elígelo aquí.",
            color = Bmw.TextDim,
            fontSize = 14.sp,
        )

        state.error?.let { Text(it, color = Bmw.MRed, fontSize = 14.sp) }

        when {
            connecting -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                Text("Conectando y buscando protocolo…", color = Bmw.Text)
            }

            !permissionGranted -> Button(onClick = onRequestPermission) {
                Text("Dar permiso de Bluetooth")
            }

            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("Adaptadores emparejados", Modifier.weight(1f))
                    TextButton(onClick = onRefresh) { Text("Actualizar") }
                }
                if (devices.isEmpty()) {
                    Text("No hay dispositivos emparejados.", color = Bmw.TextDim)
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(devices, key = { it.address }) { device -> DeviceRow(device, onConnect) }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        if (!connecting) {
            OutlinedButton(onClick = onSimulate, modifier = Modifier.fillMaxWidth()) {
                Text("Coche simulado (sin adaptador)")
            }
        }
    }
}

@Composable
private fun DeviceRow(device: DeviceItem, onConnect: (DeviceItem) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Bmw.Surface)
            .clickable { onConnect(device) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(device.name, color = Bmw.Text, fontSize = 16.sp)
        Text(device.address, color = Bmw.TextDim, fontSize = 12.sp)
    }
}
