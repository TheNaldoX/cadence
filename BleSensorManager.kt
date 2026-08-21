package fr.velo.cadence.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Un capteur decouvert pendant le balayage. */
data class DiscoveredSensor(
    val address: String,
    val name: String,
    val kind: SensorType,
    val rssi: Int,
)

enum class SensorType(val label: String) {
    HEART_RATE("Cardio"),
    POWER("Puissance"),
    SPEED_CADENCE("Vitesse / cadence"),
}

/** Valeurs consolidees venant des capteurs connectes. */
data class SensorReadings(
    val heartRate: Int? = null,
    val power: Int? = null,
    val cadence: Int? = null,
    val wheelSpeedMps: Double? = null,
    val connected: Set<SensorType> = emptySet(),
)

/**
 * Gestion des capteurs Bluetooth Low Energy.
 *
 * Une connexion GATT est ouverte par capteur ; les notifications sont
 * activees sur la caracteristique de mesure du profil correspondant. Les
 * capteurs se reconnectent automatiquement, un capteur de puissance qui se
 * met en veille au feu rouge ne doit pas obliger a refaire l'appairage.
 */
@SuppressLint("MissingPermission")
class BleSensorManager(private val context: Context) {

    private companion object {
        const val TAG = "BleSensorManager"
        const val SCAN_TIMEOUT_MS = 15_000L
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private val _readings = MutableStateFlow(SensorReadings())
    val readings: StateFlow<SensorReadings> = _readings.asStateFlow()

    private val _discovered = MutableStateFlow<List<DiscoveredSensor>>(emptyList())
    val discovered: StateFlow<List<DiscoveredSensor>> = _discovered.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val connections = mutableMapOf<String, BluetoothGatt>()
    private val revolutions = RevolutionTracker()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    val isSupported: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    val isEnabled: Boolean get() = adapter?.isEnabled == true

    fun hasPermissions(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return required.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    // ------------------------------------------------------------------ scan

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val uuids = result.scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
            val kind = when {
                SensorProfiles.HEART_RATE_SERVICE in uuids -> SensorType.HEART_RATE
                SensorProfiles.CYCLING_POWER_SERVICE in uuids -> SensorType.POWER
                SensorProfiles.CSC_SERVICE in uuids -> SensorType.SPEED_CADENCE
                else -> return
            }
            val sensor = DiscoveredSensor(
                address = device.address,
                name = device.name ?: result.scanRecord?.deviceName ?: "Capteur",
                kind = kind,
                rssi = result.rssi,
            )
            _discovered.update { current ->
                if (current.any { it.address == sensor.address }) current
                else (current + sensor).sortedByDescending { it.rssi }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Échec du balayage BLE : $errorCode")
            _scanning.value = false
        }
    }

    fun startScan() {
        if (!isSupported || !isEnabled || !hasPermissions()) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        _discovered.value = emptyList()
        _scanning.value = true

        val filters = listOf(
            SensorProfiles.HEART_RATE_SERVICE,
            SensorProfiles.CYCLING_POWER_SERVICE,
            SensorProfiles.CSC_SERVICE,
        ).map { ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build() }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        runCatching { scanner.startScan(filters, settings, scanCallback) }
            .onFailure { _scanning.value = false }

        handler.postDelayed({ stopScan() }, SCAN_TIMEOUT_MS)
    }

    fun stopScan() {
        if (!_scanning.value) return
        _scanning.value = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    // ------------------------------------------------------------- connexion

    fun connect(address: String) {
        if (!isSupported || !hasPermissions()) return
        if (connections.containsKey(address)) return
        val device: BluetoothDevice = runCatching {
            adapter?.getRemoteDevice(address)
        }.getOrNull() ?: return
        val gatt = device.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
        if (gatt != null) connections[address] = gatt
    }

    fun disconnect(address: String) {
        connections.remove(address)?.let { gatt ->
            runCatching {
                gatt.disconnect()
                gatt.close()
            }
        }
    }

    fun disconnectAll() {
        connections.keys.toList().forEach { disconnect(it) }
        _readings.value = SensorReadings()
        revolutions.reset()
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val kinds = kindsOf(gatt)
                    _readings.update { it.copy(connected = it.connected - kinds) }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val subscriptions = listOf(
                SensorProfiles.HEART_RATE_SERVICE to SensorProfiles.HEART_RATE_MEASUREMENT,
                SensorProfiles.CYCLING_POWER_SERVICE to SensorProfiles.CYCLING_POWER_MEASUREMENT,
                SensorProfiles.CSC_SERVICE to SensorProfiles.CSC_MEASUREMENT,
            )
            for ((serviceUuid, characteristicUuid) in subscriptions) {
                val characteristic = gatt.getService(serviceUuid)
                    ?.getCharacteristic(characteristicUuid) ?: continue
                enableNotifications(gatt, characteristic)
                // Une seule souscription a la fois : les piles BLE Android ne
                // supportent pas plusieurs ecritures GATT simultanees.
                break
            }
            val kinds = kindsOf(gatt)
            _readings.update { it.copy(connected = it.connected + kinds) }
        }

        @Deprecated("Signature requise pour les versions d'Android antérieures à 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            handleValue(characteristic.uuid, characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleValue(characteristic.uuid, value)
        }
    }

    private fun kindsOf(gatt: BluetoothGatt): Set<SensorType> = buildSet {
        gatt.services?.forEach { service ->
            when (service.uuid) {
                SensorProfiles.HEART_RATE_SERVICE -> add(SensorType.HEART_RATE)
                SensorProfiles.CYCLING_POWER_SERVICE -> add(SensorType.POWER)
                SensorProfiles.CSC_SERVICE -> add(SensorType.SPEED_CADENCE)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(SensorProfiles.CLIENT_CHARACTERISTIC_CONFIG)
            ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun handleValue(uuid: java.util.UUID, value: ByteArray) {
        when (uuid) {
            SensorProfiles.HEART_RATE_MEASUREMENT -> {
                SensorProfiles.parseHeartRate(value)?.let { bpm ->
                    if (bpm in 25..240) _readings.update { it.copy(heartRate = bpm) }
                }
            }
            SensorProfiles.CYCLING_POWER_MEASUREMENT -> {
                SensorProfiles.parseCyclingPower(value)?.let { measurement ->
                    if (measurement.cumulativeCrankRevolutions != null &&
                        measurement.lastCrankEventTime != null
                    ) {
                        revolutions.onCrank(
                            measurement.cumulativeCrankRevolutions,
                            measurement.lastCrankEventTime,
                        )
                    }
                    _readings.update {
                        it.copy(
                            power = measurement.watts.coerceIn(0, 2500),
                            cadence = revolutions.cadence().takeIf { rpm -> rpm > 0 } ?: it.cadence,
                        )
                    }
                }
            }
            SensorProfiles.CSC_MEASUREMENT -> {
                SensorProfiles.parseCsc(value)?.let { measurement ->
                    if (measurement.cumulativeCrankRevolutions != null &&
                        measurement.lastCrankEventTime != null
                    ) {
                        revolutions.onCrank(
                            measurement.cumulativeCrankRevolutions,
                            measurement.lastCrankEventTime,
                        )
                    }
                    if (measurement.cumulativeWheelRevolutions != null &&
                        measurement.lastWheelEventTime != null
                    ) {
                        revolutions.onWheel(
                            measurement.cumulativeWheelRevolutions,
                            measurement.lastWheelEventTime,
                        )
                    }
                    _readings.update {
                        it.copy(
                            cadence = revolutions.cadence().takeIf { rpm -> rpm > 0 } ?: it.cadence,
                            wheelSpeedMps = revolutions.speed().takeIf { s -> s > 0.0 },
                        )
                    }
                }
            }
        }
    }
}
