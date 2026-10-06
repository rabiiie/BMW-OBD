package com.rabie.bmwobd

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import androidx.lifecycle.AndroidViewModel
import com.rabie.bmwobd.obd.BluetoothSppTransport
import com.rabie.bmwobd.obd.SimulatedTransport
import com.rabie.bmwobd.settings.AppSettings
import com.rabie.bmwobd.trips.TripStore
import com.rabie.bmwobd.vehicle.Vehicle
import com.rabie.bmwobd.vehicle.VehicleStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DeviceItem(val name: String, val address: String)

class ObdViewModel(app: Application) : AndroidViewModel(app) {

    private val controller = (app as BmwObdApp).controller

    val trips: TripStore = (app as BmwObdApp).trips
    val state: StateFlow<LiveState> = controller.state
    val log: StateFlow<List<String>> = controller.log
    val diagnostics: StateFlow<DiagnosticsState> = controller.diagnostics

    private val _devices = MutableStateFlow<List<DeviceItem>>(emptyList())
    val devices: StateFlow<List<DeviceItem>> = _devices.asStateFlow()

    private var bonded: Map<String, BluetoothDevice> = emptyMap()

    @SuppressLint("MissingPermission")
    fun refreshDevices() {
        val manager = getApplication<Application>().getSystemService(BluetoothManager::class.java)
        val found: Set<BluetoothDevice> = try {
            manager?.adapter?.bondedDevices.orEmpty()
        } catch (e: SecurityException) {
            emptySet()
        }
        bonded = found.associateBy { it.address }
        _devices.value = found.map { DeviceItem(it.name ?: it.address, it.address) }.sortedBy { it.name }
    }

    fun connect(address: String) {
        val device = bonded[address] ?: return
        controller.connect(BluetoothSppTransport(device), simulated = false)
    }

    fun connectSimulator() = controller.connect(SimulatedTransport(), simulated = true)

    fun disconnect() = controller.disconnect()

    fun sendCommand(command: String) = controller.sendCommand(command)

    private val bubble = (app as BmwObdApp).bubble
    val bubbleEnabled: StateFlow<Boolean> = bubble.enabled

    /** Devuelve false si falta el permiso de mostrar sobre otras apps y hay que ir a concederlo. */
    fun toggleBubble(): Boolean {
        val enable = !bubble.enabled.value
        bubble.setEnabled(enable)
        return !enable || bubble.hasPermission()
    }

    val vehicles: VehicleStore = (app as BmwObdApp).vehicles

    fun updateVehicle(vehicle: Vehicle) = controller.updateVehicle(vehicle)

    private val settingsStore = (app as BmwObdApp).settings
    val settings: StateFlow<AppSettings> = settingsStore.state

    fun updateSettings(transform: (AppSettings) -> AppSettings) = settingsStore.update(transform)

    fun probe() = controller.probe()

    fun readDiagnostics() = controller.readDiagnostics()

    fun clearDtcs() = controller.clearDtcs()
}
