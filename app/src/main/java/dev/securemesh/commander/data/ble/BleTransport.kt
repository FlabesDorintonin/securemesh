package dev.securemesh.commander.data.ble

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.securemesh.commander.data.transport.MeshTransport
import dev.securemesh.commander.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BleTransport(
    private val context: Context,
    private val config: BleProtocolConfig = BleProtocolConfig.ProtocolV01,
    private val codec: SecureMeshBleProtocolV01Codec = SecureMeshBleProtocolV01Codec(),
    private val now: () -> Long = System::currentTimeMillis,
) : MeshTransport {
    private enum class HandshakePhase {
        IDLE, WAITING_BOND, REQUESTING_MTU, SUBSCRIBING_RESPONSE, SUBSCRIBING_EVENT, READING_INFO, READY,
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bluetoothManager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val matcher = SecureMeshDeviceMatcher(config)
    private val scanResults = ConcurrentHashMap<String, DiscoveredDevice>()
    private val requestManager = BleRequestManager(maxPending = 16, timeoutMs = 5_000L)
    private val responseReassembler = SecureMeshBleFragmentation.Reassembler(config.reassemblyTimeoutMs)
    private val eventReassembler = SecureMeshBleFragmentation.Reassembler(config.reassemblyTimeoutMs)
    private val writeMutex = Mutex()
    private val syncMutex = Mutex()
    private val stateLock = Any()

    private var scanTimeoutJob: Job? = null
    private var connectionTimeoutJob: Job? = null
    private var disconnectTimeoutJob: Job? = null
    private var pairingTimeoutJob: Job? = null
    private var mtuFallbackJob: Job? = null
    private var gatt: BluetoothGatt? = null
    private var connectingDevice: DiscoveredDevice? = null
    private var secureService: BluetoothGattService? = null
    private var infoCharacteristic: BluetoothGattCharacteristic? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var responseCharacteristic: BluetoothGattCharacteristic? = null
    private var eventCharacteristic: BluetoothGattCharacteristic? = null
    private var writeAwaiter: CompletableDeferred<Int>? = null
    private var handshakePhase = HandshakePhase.IDLE
    private var infoReadAttempts = 0
    private var negotiatedMtu = 23
    private var responseSubscribed = false
    private var eventSubscribed = false
    private var nextTransportId = 1
    private var bondReceiverRegistered = false

    private val _connectionState = MutableStateFlow<MeshConnectionState>(MeshConnectionState.Idle)
    override val connectionState = _connectionState.asStateFlow()
    private val _session = MutableStateFlow<SecureMeshSession?>(null)
    override val session = _session.asStateFlow()
    private val _demoProfile = MutableStateFlow<DemoProfile?>(null)
    override val demoProfile = _demoProfile.asStateFlow()
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val discoveredDevices = _discoveredDevices.asStateFlow()
    private val _nodes = MutableStateFlow<List<MeshNode>>(emptyList())
    override val nodes = _nodes.asStateFlow()
    private val _topology = MutableStateFlow(MeshTopology(emptyList(), emptyList(), 0L))
    override val topology = _topology.asStateFlow()
    private val _messages = MutableStateFlow<List<MeshMessage>>(emptyList())
    override val messages = _messages.asStateFlow()
    private val _routes = MutableStateFlow<List<MeshRoute>>(emptyList())
    override val routes = _routes.asStateFlow()
    private val _events = MutableStateFlow<List<MeshEvent>>(emptyList())
    override val events = _events.asStateFlow()
    private val _activeFieldTest = MutableStateFlow<FieldTestSession?>(null)
    override val activeFieldTest = _activeFieldTest.asStateFlow()
    private val _activeSos = MutableStateFlow<SosAlert?>(null)
    override val activeSos = _activeSos.asStateFlow()
    private val _bleDiagnostics = MutableStateFlow<BleDiagnostics?>(BleDiagnostics())
    override val bleDiagnostics = _bleDiagnostics.asStateFlow()
    private val _deviceUiState = MutableStateFlow<DeviceUiState?>(null)
    override val deviceUiState = _deviceUiState.asStateFlow()

    override suspend fun start() {
        _connectionState.value = environmentState()
    }

    override suspend fun stop() {
        stopScan()
        cancelConnectionJobs()
        requestManager.failAll(IllegalStateException("SecureMesh BLE transport stopped"))
        resetProtocolState()
        closeGatt()
        connectingDevice = null
        _session.value = null
        _deviceUiState.value = null
        _demoProfile.value = null
        _connectionState.value = environmentState()
    }

    override suspend fun startScan(durationMs: Long) {
        when (val environment = environmentState()) {
            MeshConnectionState.Idle -> Unit
            else -> { _connectionState.value = environment; return }
        }
        val boundedDurationMs = durationMs.coerceIn(5_000L, 30_000L)
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            fail(MeshErrorCode.SCAN_FAILED, "BluetoothLeScanner unavailable", "BLE-сканер недоступен")
            return
        }
        stopScanInternal(updateState = false)
        scanResults.clear()
        _discoveredDevices.value = emptyList()
        val start = now()
        _connectionState.value = MeshConnectionState.Scanning(start, start + boundedDurationMs)
        updateDiagnostics { it.copy(gattState = "SCANNING") }
        try {
            scanner.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCallback,
            )
            scanTimeoutJob = scope.launch {
                delay(boundedDurationMs)
                stopScanInternal(updateState = true)
            }
        } catch (_: SecurityException) {
            _connectionState.value = MeshConnectionState.PermissionRequired(requiredPermissions())
        } catch (t: Throwable) {
            fail(MeshErrorCode.SCAN_FAILED, t.message ?: t::class.java.simpleName, "Не удалось запустить BLE-поиск")
        }
    }

    override suspend fun stopScan() = stopScanInternal(updateState = true)

    override suspend fun connect(device: DiscoveredDevice) {
        when (val environment = environmentState()) {
            MeshConnectionState.Idle -> Unit
            else -> { _connectionState.value = environment; return }
        }
        stopScanInternal(updateState = false)
        cancelConnectionJobs()
        requestManager.failAll(IllegalStateException("Starting a new BLE connection"))
        resetProtocolState()
        closeGatt()
        connectingDevice = device
        _session.value = null
        _deviceUiState.value = null
        _connectionState.value = MeshConnectionState.Connecting(device)
        updateDiagnostics {
            BleDiagnostics(
                bleAddress = device.address,
                gattState = "CONNECTING",
                bonded = device.bondStatus == BondStatus.BONDED,
            )
        }

        val remote = try { adapter?.getRemoteDevice(device.address) } catch (_: Throwable) { null }
        if (remote == null) {
            fail(MeshErrorCode.CONNECTION_LOST, "Remote device unavailable", "Выбранное BLE-устройство недоступно")
            return
        }

        try {
            ensureBondReceiver()
            val newGatt = remote.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (newGatt == null) {
                fail(MeshErrorCode.CONNECTION_LOST, "connectGatt returned null", "Не удалось открыть BLE GATT")
                return
            }
            gatt = newGatt
            connectionTimeoutJob = scope.launch {
                delay(15_000L)
                if (handshakePhase != HandshakePhase.READY && _session.value == null) {
                    failAndClose(MeshErrorCode.CONNECTION_TIMEOUT, "SecureMesh GATT handshake timeout", "Подключение SecureMesh не завершилось вовремя")
                }
            }
        } catch (_: SecurityException) {
            _connectionState.value = MeshConnectionState.PermissionRequired(requiredPermissions())
        } catch (t: Throwable) {
            failAndClose(MeshErrorCode.CONNECTION_LOST, t.message ?: "connectGatt failed", "Не удалось подключиться к устройству")
        }
    }

    override suspend fun disconnect() {
        connectionTimeoutJob?.cancel()
        pairingTimeoutJob?.cancel()
        mtuFallbackJob?.cancel()
        requestManager.failAll(IllegalStateException("SecureMesh BLE disconnected"))
        val currentGatt = gatt
        if (currentGatt == null) {
            connectingDevice = null
            _session.value = null
            resetProtocolState()
            _connectionState.value = MeshConnectionState.Disconnected("No active BLE link")
            return
        }
        _connectionState.value = MeshConnectionState.Disconnecting
        updateDiagnostics { it.copy(gattState = "DISCONNECTING", secureSessionState = SecureSessionState.NOT_AUTHENTICATED) }
        try {
            currentGatt.disconnect()
            disconnectTimeoutJob = scope.launch {
                delay(3_000L)
                if (_connectionState.value is MeshConnectionState.Disconnecting) {
                    closeGatt()
                    connectingDevice = null
                    _session.value = null
                    resetProtocolState()
                    _connectionState.value = MeshConnectionState.Disconnected("BLE disconnect timeout; local GATT closed")
                }
            }
        } catch (_: SecurityException) {
            closeGatt()
            connectingDevice = null
            _session.value = null
            resetProtocolState()
            _connectionState.value = MeshConnectionState.Disconnected("Permission revoked")
        }
    }

    override suspend fun sendMessage(destination: NodeId, payload: String): Result<MessageId> = runCatching {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..70) { "Сообщение SecureMesh должно занимать 1..70 байт UTF-8" }
        val response = command(SecureMeshBleCommand.SendMessage(destination, bytes)).getOrThrow()
        val accepted = codec.parseSendAccepted(response).getOrThrow()
        val id = wireId(accepted.messageId)
        val local = requireReadySession().localNodeIdentity.nodeId
        val hop = TransmissionHop(
            from = local,
            to = accepted.firstNextHop,
            frameId = null,
            ackState = HopAckState.PENDING,
            retries = 0,
            rssi = null,
            snr = null,
            timestampEpochMs = now(),
        )
        val message = MeshMessage(
            id = id,
            origin = local,
            destination = destination,
            payload = payload,
            createdAtEpochMs = now(),
            progressState = MessageDeliveryState.QUEUED,
            // Firmware v0.1 has no ordinary-message end-to-end delivery receipt.
            finalState = MessageFinalState.UNKNOWN,
            hopTrace = listOf(hop),
        )
        _messages.value = upsertMessage(_messages.value, message)
        id
    }

    override suspend fun addStaticRoute(destination: NodeId, via: NodeId): Result<Unit> = runCatching {
        val response = command(SecureMeshBleCommand.AddStaticRoute(destination, via)).getOrThrow()
        requireOk(response)
        syncRoutes()
    }

    override suspend fun removeRoute(destination: NodeId): Result<Unit> = runCatching {
        val response = command(SecureMeshBleCommand.RemoveStaticRoute(destination)).getOrThrow()
        requireOk(response)
        syncRoutes()
    }

    override suspend fun startFieldTest(config: FieldTestConfig): Result<String> = runCatching {
        val session = requireReadySession()
        require(config.source == session.localNodeIdentity.nodeId) { "Field test source must be local node" }
        val response = command(
            SecureMeshBleCommand.StartFieldTest(
                target = config.target,
                count = config.packetCount,
                intervalMs = config.intervalMs,
                size = config.payloadBytes,
                directOnly = config.mode == FieldTestMode.DIRECT,
            )
        ).getOrThrow()
        val status = codec.parseFieldTestStatus(response, BleOpcode.START_FIELD_TEST).getOrThrow()
        val seed = FieldTestSession(
            id = status.testId.toString(),
            config = config.copy(mode = if (status.mode == 1) FieldTestMode.DIRECT else FieldTestMode.ROUTED),
            startedAtEpochMs = now(),
        )
        _activeFieldTest.value = SecureMeshBleV01DomainMapping.fieldTest(status, session.localNodeIdentity.nodeId, seed, now())
        status.testId.toString()
    }

    override suspend fun stopFieldTest() {
        val result = command(SecureMeshBleCommand.StopFieldTest)
        result.onSuccess(::requireOk)
        if (result.isSuccess) syncFieldTestStatus()
    }

    override suspend fun acknowledgeSos(id: String) {
        // Protocol v0.1 has no SOS command/capability.
    }

    override suspend fun refreshDeviceUiState(): Result<DeviceUiState> = runCatching {
        val session = requireReadySession()
        require(session.supports(DeviceCapability.UI_OS)) { "Firmware does not advertise UI_OS capability" }
        val response = command(SecureMeshBleCommand.GetUiState).getOrThrow()
        val payload = codec.parseUiState(response).getOrThrow()
        require(payload.localNodeId == session.localNodeIdentity.nodeId) { "GET_UI_STATE local node mismatch" }
        SecureMeshBleV01DomainMapping.deviceUiState(payload, now()).also { _deviceUiState.value = it }
    }

    override suspend fun sendDeviceUiAction(action: DeviceUiAction): Result<DeviceUiState> = runCatching {
        val session = requireReadySession()
        require(session.supports(DeviceCapability.UI_OS)) { "Firmware does not advertise UI_OS capability" }
        val response = command(SecureMeshBleCommand.UiAction(action.wire)).getOrThrow()
        val payload = codec.parseUiState(response, BleOpcode.UI_ACTION).getOrThrow()
        require(payload.localNodeId == session.localNodeIdentity.nodeId) { "UI_ACTION local node mismatch" }
        SecureMeshBleV01DomainMapping.deviceUiState(payload, now()).also { _deviceUiState.value = it }
    }

    private suspend fun command(command: SecureMeshBleCommand): Result<SecureMeshBleFrame.Response> {
        requireReadySession()
        val characteristic = commandCharacteristic ?: return Result.failure(IllegalStateException("COMMAND characteristic unavailable"))
        val currentGatt = gatt ?: return Result.failure(IllegalStateException("GATT disconnected"))
        val handle = requestManager.allocate(command.opcode).getOrElse { return Result.failure(it) }
        val packet = codec.encodeCommand(handle.requestId, command).getOrElse {
            requestManager.cancel(handle, it)
            return Result.failure(it)
        }
        val transportId = allocateTransportId()
        val fragments = SecureMeshBleFragmentation.fragment(packet, negotiatedMtu, transportId).getOrElse {
            requestManager.cancel(handle, it)
            return Result.failure(it)
        }
        updateDiagnostics { it.copy(lastCommandRequestId = handle.requestId) }

        val writeResult = runCatching {
            writeMutex.withLock {
                for (fragment in fragments) writeCommandFragment(currentGatt, characteristic, fragment)
            }
        }
        if (writeResult.isFailure) {
            val cause = writeResult.exceptionOrNull() ?: IllegalStateException("BLE command write failed")
            requestManager.cancel(handle, cause)
            return Result.failure(cause)
        }

        val response = requestManager.await(handle)
        response.onSuccess { frame ->
            updateDiagnostics { it.copy(lastResponse = "#${frame.requestId} opcode=${frame.rawOpcode} status=${frame.rawStatus}") }
        }
        return response
    }

    private suspend fun writeCommandFragment(
        currentGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray,
    ) {
        val waiter = CompletableDeferred<Int>()
        synchronized(stateLock) {
            check(writeAwaiter == null) { "Concurrent GATT write callback waiter" }
            writeAwaiter = waiter
        }
        val started = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                currentGatt.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = bytes
                @Suppress("DEPRECATION")
                currentGatt.writeCharacteristic(characteristic)
            }
        } catch (t: Throwable) {
            synchronized(stateLock) { if (writeAwaiter === waiter) writeAwaiter = null }
            throw t
        }
        if (!started) {
            synchronized(stateLock) { if (writeAwaiter === waiter) writeAwaiter = null }
            error("Android rejected GATT COMMAND write")
        }
        val status = try { withTimeout(4_000L) { waiter.await() } }
        finally { synchronized(stateLock) { if (writeAwaiter === waiter) writeAwaiter = null } }
        require(status == BluetoothGatt.GATT_SUCCESS) { "GATT COMMAND write status=$status" }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handleScanResult(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handleScanResult)
        override fun onScanFailed(errorCode: Int) {
            fail(MeshErrorCode.SCAN_FAILED, "Android scan error $errorCode", "BLE-поиск завершился ошибкой ($errorCode)")
        }
    }

    private fun handleScanResult(result: ScanResult) {
        try {
            val record = result.scanRecord
            val name = record?.deviceName ?: result.device.name
            val services = record?.serviceUuids?.map { it.uuid }?.toSet().orEmpty()
            val manufacturer = buildMap<Int, ByteArray> {
                val array = record?.manufacturerSpecificData ?: return@buildMap
                for (i in 0 until array.size()) put(array.keyAt(i), array.valueAt(i))
            }
            val match = matcher.match(AdvertisementSnapshot(name, services, manufacturer))
            if (!config.showAllBleDevices && match.classification == DeviceClassification.UNKNOWN_BLE) return
            val address = result.device.address
            val bond = result.device.bondState.toBondStatus()
            scanResults[address] = DiscoveredDevice(
                address = address,
                advertisedName = name,
                rssi = result.rssi,
                lastSeenEpochMs = now(),
                classification = match.classification,
                bondStatus = bond,
                protocolVersion = match.protocolVersion,
                deviceType = match.deviceType,
                matchReasons = match.reasons,
            )
            _discoveredDevices.value = scanResults.values.sortedByDescending { it.rssi }
            val end = when (val state = _connectionState.value) {
                is MeshConnectionState.Scanning -> state.endsAtEpochMs
                is MeshConnectionState.DeviceFound -> state.scanEndsAtEpochMs
                else -> now()
            }
            _connectionState.value = MeshConnectionState.DeviceFound(_discoveredDevices.value.size, end)
        } catch (_: SecurityException) {
            _connectionState.value = MeshConnectionState.PermissionRequired(requiredPermissions())
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
            } ?: return
            if (device.address != connectingDevice?.address) return

            if (action == BluetoothDevice.ACTION_PAIRING_REQUEST) {
                val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                updateDiagnostics { it.copy(gattState = "PAIRING_REQUEST", lastResponse = "Android pairing request variant=$variant") }
                connectingDevice?.let { _connectionState.value = MeshConnectionState.PairingRequired(it, now() + 45_000L) }
                return
            }
            if (action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return

            val newState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
            val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
            updateDiagnostics { it.copy(bonded = newState == BluetoothDevice.BOND_BONDED) }
            when (newState) {
                BluetoothDevice.BOND_BONDING -> {
                    handshakePhase = HandshakePhase.WAITING_BOND
                    connectingDevice?.let { _connectionState.value = MeshConnectionState.PairingRequired(it, now() + 45_000L) }
                }
                BluetoothDevice.BOND_BONDED -> {
                    pairingTimeoutJob?.cancel()
                    scope.launch { continueAfterBond() }
                }
                BluetoothDevice.BOND_NONE -> if (previous == BluetoothDevice.BOND_BONDING) {
                    pairingTimeoutJob?.cancel()
                    failAndClose(
                        MeshErrorCode.PAIRING_FAILED,
                        "System BLE bonding failed",
                        "Сопряжение SecureMesh не подтверждено. Если устройство уже было сохранено в Bluetooth — забудь его и повтори подключение",
                    )
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(callbackGatt: BluetoothGatt, status: Int, newState: Int) {
            val device = connectingDevice ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                requestManager.failAll(IllegalStateException("GATT status $status"))
                failAndClose(MeshErrorCode.CONNECTION_LOST, "GATT status $status", "BLE-соединение потеряно")
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    updateDiagnostics { it.copy(gattState = "CONNECTED") }
                    _connectionState.value = MeshConnectionState.DiscoveringServices(device)
                    try {
                        if (!callbackGatt.discoverServices()) {
                            markProtocolUnavailable("GATT service discovery could not start")
                        }
                    } catch (_: SecurityException) {
                        _connectionState.value = MeshConnectionState.PermissionRequired(requiredPermissions())
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    cancelConnectionJobs()
                    requestManager.failAll(IllegalStateException("BLE link disconnected"))
                    _session.value = null
                    resetProtocolState()
                    closeGatt()
                    connectingDevice = null
                    _connectionState.value = MeshConnectionState.Disconnected("BLE link disconnected")
                    updateDiagnostics { it.copy(gattState = "DISCONNECTED", secureSessionState = SecureSessionState.NOT_AUTHENTICATED) }
                }
            }
        }

        override fun onServicesDiscovered(callbackGatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                markProtocolUnavailable("Service discovery status $status")
                return
            }
            val service = callbackGatt.getService(config.serviceUuid)
            if (service == null) { markProtocolUnavailable("SecureMesh service UUID missing"); return }
            val info = service.getCharacteristic(config.infoCharacteristicUuid)
            val command = service.getCharacteristic(config.commandCharacteristicUuid)
            val response = service.getCharacteristic(config.responseCharacteristicUuid)
            val event = service.getCharacteristic(config.eventCharacteristicUuid)
            if (info == null || command == null || response == null || event == null) {
                markProtocolUnavailable("SecureMesh v0.1 characteristic set incomplete")
                return
            }
            secureService = service
            infoCharacteristic = info
            commandCharacteristic = command
            responseCharacteristic = response
            eventCharacteristic = event
            scope.launch { beginSystemBonding(callbackGatt.device) }
        }

        override fun onMtuChanged(callbackGatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && mtu >= 23) negotiatedMtu = mtu
            updateDiagnostics { it.copy(mtu = negotiatedMtu) }
            if (handshakePhase == HandshakePhase.REQUESTING_MTU) {
                mtuFallbackJob?.cancel()
                subscribeResponse()
            }
        }

        override fun onDescriptorWrite(callbackGatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failAndClose(MeshErrorCode.PROTOCOL_MISMATCH, "CCCD write status $status", "Не удалось включить SecureMesh notifications")
                return
            }
            when (descriptor.characteristic.uuid) {
                config.responseCharacteristicUuid -> {
                    responseSubscribed = true
                    updateDiagnostics { it.copy(responseSubscribed = true) }
                    subscribeEvent()
                }
                config.eventCharacteristicUuid -> {
                    eventSubscribed = true
                    updateDiagnostics { it.copy(eventSubscribed = true) }
                    readInfo()
                }
            }
        }

        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicRead(callbackGatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            handleCharacteristicRead(characteristic.uuid, characteristic.value ?: byteArrayOf(), status)
        }

        override fun onCharacteristicRead(callbackGatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handleCharacteristicRead(characteristic.uuid, value, status)
        }

        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicChanged(callbackGatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleNotification(characteristic.uuid, characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(callbackGatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotification(characteristic.uuid, value)
        }

        override fun onCharacteristicWrite(callbackGatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid != config.commandCharacteristicUuid) return
            synchronized(stateLock) { writeAwaiter }?.complete(status)
        }
    }

    private suspend fun beginSystemBonding(device: BluetoothDevice) {
        val uiDevice = connectingDevice ?: return
        updateDiagnostics { it.copy(gattState = "SERVICE_VERIFIED", bonded = device.bondState == BluetoothDevice.BOND_BONDED) }
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> continueAfterBond()
            BluetoothDevice.BOND_BONDING -> waitForBond(uiDevice)
            else -> {
                // Firmware 0.6.3 is the primary SMP initiator. It enters Pairing and calls
                // NimBLEDevice::startSecurity(connHandle). Give the peripheral Security
                // Request time to open Android's native passkey dialog before createBond().
                // Starting both sides at once races SMP on some OEM Bluetooth stacks.
                waitForBond(uiDevice)
                delay(1_200L)
                if (handshakePhase != HandshakePhase.WAITING_BOND) return
                val bondState = try { device.bondState } catch (_: SecurityException) { BluetoothDevice.BOND_NONE }
                if (bondState == BluetoothDevice.BOND_BONDED) {
                    pairingTimeoutJob?.cancel()
                    continueAfterBond()
                    return
                }
                if (bondState == BluetoothDevice.BOND_BONDING) return

                // Fallback only when the peripheral Security Request was not surfaced.
                val started = try { device.createBond() } catch (_: SecurityException) { false }
                if (!started) failAndClose(
                    MeshErrorCode.PAIRING_FAILED,
                    "Peripheral SMP request was not surfaced and BluetoothDevice.createBond returned false",
                    "Android не открыл ввод кода. Удали старую пару SecureMesh в системном Bluetooth и подключись снова",
                )
            }
        }
    }

    private fun waitForBond(device: DiscoveredDevice) {
        handshakePhase = HandshakePhase.WAITING_BOND
        _connectionState.value = MeshConnectionState.PairingRequired(device, now() + 45_000L)
        updateDiagnostics { it.copy(gattState = "PAIRING", secureSessionState = SecureSessionState.AUTHENTICATING) }
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = scope.launch {
            delay(45_000L)
            if (handshakePhase == HandshakePhase.WAITING_BOND) {
                failAndClose(MeshErrorCode.PAIRING_FAILED, "BLE pairing passkey timeout", "Время ввода кода сопряжения истекло")
            }
        }
    }

    private suspend fun continueAfterBond() {
        val device = connectingDevice ?: return
        handshakePhase = HandshakePhase.REQUESTING_MTU
        _connectionState.value = MeshConnectionState.Authenticating(device)
        updateDiagnostics { it.copy(gattState = "AUTHENTICATING", bonded = true, secureSessionState = SecureSessionState.AUTHENTICATING) }
        val currentGatt = gatt ?: return
        val requested = try { currentGatt.requestMtu(config.preferredMtu) } catch (_: SecurityException) { false }
        if (!requested) {
            negotiatedMtu = 23
            subscribeResponse()
            return
        }
        mtuFallbackJob?.cancel()
        mtuFallbackJob = scope.launch {
            delay(1_500L)
            if (handshakePhase == HandshakePhase.REQUESTING_MTU) subscribeResponse()
        }
    }

    private fun subscribeResponse() {
        if (handshakePhase == HandshakePhase.SUBSCRIBING_RESPONSE || responseSubscribed) {
            if (responseSubscribed) subscribeEvent()
            return
        }
        handshakePhase = HandshakePhase.SUBSCRIBING_RESPONSE
        val characteristic = responseCharacteristic ?: return markProtocolUnavailable("RESPONSE characteristic unavailable")
        writeCccd(characteristic)
    }

    private fun subscribeEvent() {
        if (handshakePhase == HandshakePhase.SUBSCRIBING_EVENT || eventSubscribed) {
            if (eventSubscribed) readInfo()
            return
        }
        handshakePhase = HandshakePhase.SUBSCRIBING_EVENT
        val characteristic = eventCharacteristic ?: return markProtocolUnavailable("EVENT characteristic unavailable")
        writeCccd(characteristic)
    }

    private fun writeCccd(characteristic: BluetoothGattCharacteristic) {
        val currentGatt = gatt ?: return
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
            ?: return failAndClose(MeshErrorCode.PROTOCOL_MISMATCH, "CCCD missing for ${characteristic.uuid}", "SecureMesh notification descriptor отсутствует")
        try {
            if (!currentGatt.setCharacteristicNotification(characteristic, true)) {
                failAndClose(MeshErrorCode.PROTOCOL_MISMATCH, "setCharacteristicNotification false", "Не удалось включить SecureMesh notifications")
                return
            }
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                currentGatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                currentGatt.writeDescriptor(descriptor)
            }
            if (!started) failAndClose(MeshErrorCode.PROTOCOL_MISMATCH, "CCCD write did not start", "Не удалось подписаться на SecureMesh notifications")
        } catch (_: SecurityException) {
            _connectionState.value = MeshConnectionState.PermissionRequired(requiredPermissions())
        }
    }

    private fun readInfo() {
        if (!responseSubscribed || !eventSubscribed) return
        handshakePhase = HandshakePhase.READING_INFO
        val device = connectingDevice ?: return
        _connectionState.value = MeshConnectionState.IdentifyingSecureMesh(device)
        val currentGatt = gatt ?: return
        val characteristic = infoCharacteristic ?: return markProtocolUnavailable("INFO characteristic unavailable")
        try {
            if (!currentGatt.readCharacteristic(characteristic)) {
                failAndClose(MeshErrorCode.PROTOCOL_MISMATCH, "INFO read did not start", "Не удалось прочитать SecureMesh INFO")
            }
        } catch (_: SecurityException) {
            _connectionState.value = MeshConnectionState.PermissionRequired(requiredPermissions())
        }
    }

    private fun handleCharacteristicRead(uuid: UUID, value: ByteArray, status: Int) {
        if (uuid != config.infoCharacteristicUuid) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            val securityFailure = status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION || status == BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION
            val staleBond = securityFailure && connectingDevice?.bondStatus == BondStatus.BONDED
            failAndClose(
                if (securityFailure) MeshErrorCode.PAIRING_FAILED else MeshErrorCode.PROTOCOL_MISMATCH,
                "INFO GATT read status $status",
                if (staleBond) {
                    "Сохранённые BLE-ключи не совпали. Забудь SecureMesh в настройках Bluetooth и выполни на ESP32: ble bonds clear"
                } else {
                    "SecureMesh INFO недоступен после сопряжения"
                },
            )
            return
        }
        val frame = codec.decodeApplicationPacket(value).getOrElse {
            incrementMalformed("INFO: ${it.message}")
            markProtocolUnavailable("Malformed SecureMesh INFO packet")
            return
        }
        val response = frame as? SecureMeshBleFrame.Response
        if (response == null || response.opcode != BleOpcode.GET_INFO || response.requestId != 0) {
            incrementMalformed("INFO has wrong envelope")
            markProtocolUnavailable("INFO envelope mismatch")
            return
        }
        val info = codec.parseInfo(response).getOrElse {
            incrementMalformed("INFO payload: ${it.message}")
            markProtocolUnavailable("INFO payload mismatch")
            return
        }
        if (info.bleProtocolVersion !in config.supportedProtocolVersions) {
            markProtocolUnavailable("Unsupported BLE protocol version ${info.bleProtocolVersion}")
            return
        }
        if (!info.authenticated) {
            failAndClose(MeshErrorCode.PAIRING_FAILED, "INFO security flags authenticated=0", "BLE-соединение не прошло MITM-аутентификацию")
            return
        }
        if (info.bleState != BLE_STATE_PROTOCOL_READY) {
            // A secure-link callback and main-loop state transition may be separated by a few milliseconds.
            if (info.bleState == BLE_STATE_SECURE_LINK && infoReadAttempts < 3) {
                infoReadAttempts++
                scope.launch { delay(180L); readInfo() }
                return
            }
            failAndClose(MeshErrorCode.PROTOCOL_MISMATCH, "Firmware BLE state ${info.bleState}, expected PROTOCOL_READY", "SecureMesh protocol ещё не перешёл в PROTOCOL_READY")
            return
        }
        infoReadAttempts = 0
        establishSecureSession(info)
    }

    private fun establishSecureSession(info: BleInfoPayload) {
        val identity = SecureMeshBleV01DomainMapping.identity(info)
        _connectionState.value = MeshConnectionState.SyncingSession(identity)
        val permissions = SecureMeshBleV01DomainMapping.permissions(info.permissionMask)
        val secureSession = SecureMeshSession(
            localNodeIdentity = identity,
            connectionState = SecureSessionConnectionState.SECURE_SESSION_ESTABLISHED,
            authenticationState = AuthenticationState.AUTHENTICATED,
            grantedPermissions = permissions,
            connectedSinceEpochMs = now(),
        )
        _session.value = secureSession
        val original = connectingDevice ?: return
        val verifiedDevice = original.copy(
            classification = DeviceClassification.TRUSTED_SECUREMESH,
            bondStatus = if (info.bonded) BondStatus.BONDED else original.bondStatus,
            secureMeshNodeId = identity.nodeId,
            protocolVersion = info.bleProtocolVersion,
            deviceType = identity.role.name,
            matchReasons = original.matchReasons + "authenticated-info",
        )
        connectingDevice = verifiedDevice
        scanResults[verifiedDevice.address] = verifiedDevice
        _discoveredDevices.value = scanResults.values.sortedByDescending { it.rssi }
        _nodes.value = listOf(MeshNode(identity, online = true, lastSeenEpochMs = now()))
        _topology.value = MeshTopology(listOf(identity.nodeId), emptyList(), now())
        handshakePhase = HandshakePhase.READY
        connectionTimeoutJob?.cancel()
        _connectionState.value = MeshConnectionState.Connected(
            device = verifiedDevice,
            linkRssi = verifiedDevice.rssi,
            secureSession = SecureSessionState.ESTABLISHED,
            protocolConfigured = true,
        )
        updateDiagnostics {
            it.copy(
                nodeId = identity.nodeId,
                bleAddress = verifiedDevice.address,
                gattState = "PROTOCOL_READY",
                bonded = info.bonded,
                protocolVersion = info.bleProtocolVersion,
                firmwareVersion = info.firmwareVersion,
                mtu = negotiatedMtu,
                responseSubscribed = responseSubscribed,
                eventSubscribed = eventSubscribed,
                secureSessionState = SecureSessionState.ESTABLISHED,
                lastResponse = "INFO protocol=${info.bleProtocolVersion} fw=${info.firmwareVersion}",
            )
        }
        addEvent(EventCategory.SECURITY, "SECUREMESH SESSION ESTABLISHED", "Node ${identity.nodeId} · BLE protocol ${info.bleProtocolVersion}", identity.nodeId)
        scope.launch { initialSync() }
    }

    private suspend fun initialSync() = syncMutex.withLock {
        syncStatusUnlocked()
        syncNeighborsUnlocked()
        syncRoutesUnlocked()
        syncFieldTestStatusUnlocked()
        if (_session.value?.supports(DeviceCapability.UI_OS) == true) syncDeviceUiStateUnlocked()
    }

    private suspend fun syncStatus() = syncMutex.withLock { syncStatusUnlocked() }
    private suspend fun syncNeighbors() = syncMutex.withLock { syncNeighborsUnlocked() }
    private suspend fun syncRoutes() = syncMutex.withLock { syncRoutesUnlocked() }
    private suspend fun syncFieldTestStatus() = syncMutex.withLock { syncFieldTestStatusUnlocked() }
    private suspend fun syncDeviceUiState() = syncMutex.withLock { syncDeviceUiStateUnlocked() }

    private suspend fun syncStatusUnlocked() {
        val response = command(SecureMeshBleCommand.GetStatus).getOrNull() ?: return
        val status = codec.parseStatus(response).getOrNull() ?: return
        val session = _session.value ?: return
        if (status.localNodeId != session.localNodeIdentity.nodeId) {
            incrementMalformed("GET_STATUS local node mismatch")
            return
        }
        val local = _nodes.value.firstOrNull { it.id == status.localNodeId }
            ?: MeshNode(session.localNodeIdentity, true, now())
        val updated = local.copy(online = true, lastSeenEpochMs = now(), uptimeSec = status.uptimeMs / 1000L)
        _nodes.value = listOf(updated) + _nodes.value.filter { it.id != updated.id }
    }

    private suspend fun syncNeighborsUnlocked() {
        val response = command(SecureMeshBleCommand.GetNeighbors).getOrNull() ?: return
        val neighbors = codec.parseNeighbors(response).getOrNull() ?: return
        val session = _session.value ?: return
        val localId = session.localNodeIdentity.nodeId
        val local = _nodes.value.firstOrNull { it.id == localId }
            ?: MeshNode(session.localNodeIdentity, true, now())
        val neighborNodes = neighbors.map { SecureMeshBleV01DomainMapping.neighborNode(it, now()) }
        _nodes.value = listOf(local.copy(online = true, lastSeenEpochMs = now())) + neighborNodes.filter { it.id != localId }
        val links = neighbors.filter { it.nodeId != localId }.map { SecureMeshBleV01DomainMapping.neighborLink(localId, it, now()) }
        _topology.value = MeshTopology(_nodes.value.map { it.id }.distinct(), links, now())
    }

    private suspend fun syncRoutesUnlocked() {
        val response = command(SecureMeshBleCommand.GetRoutes).getOrNull() ?: return
        val routes = codec.parseRoutes(response).getOrNull() ?: return
        _routes.value = routes.mapNotNull { SecureMeshBleV01DomainMapping.route(it, now()) }
    }

    private suspend fun syncFieldTestStatusUnlocked() {
        val response = command(SecureMeshBleCommand.GetFieldTestStatus).getOrNull() ?: return
        val status = codec.parseFieldTestStatus(response).getOrNull() ?: return
        val localId = _session.value?.localNodeIdentity?.nodeId ?: return
        _activeFieldTest.value = SecureMeshBleV01DomainMapping.fieldTest(status, localId, _activeFieldTest.value, now())
    }

    private suspend fun syncDeviceUiStateUnlocked() {
        val session = _session.value ?: return
        if (!session.supports(DeviceCapability.UI_OS)) {
            _deviceUiState.value = null
            return
        }
        val response = command(SecureMeshBleCommand.GetUiState).getOrNull() ?: return
        val payload = codec.parseUiState(response).getOrNull() ?: return
        if (payload.localNodeId != session.localNodeIdentity.nodeId) {
            incrementMalformed("GET_UI_STATE local node mismatch")
            return
        }
        _deviceUiState.value = SecureMeshBleV01DomainMapping.deviceUiState(payload, now())
    }

    private fun handleNotification(uuid: UUID, fragment: ByteArray) {
        val reassembler = when (uuid) {
            config.responseCharacteristicUuid -> responseReassembler
            config.eventCharacteristicUuid -> eventReassembler
            else -> return
        }
        if (reassembler.expire(now())) incrementReassemblyError("incomplete assembly timeout")
        when (val accepted = reassembler.accept(fragment, now())) {
            SecureMeshBleFragmentation.AcceptResult.Incomplete -> Unit
            is SecureMeshBleFragmentation.AcceptResult.Rejected -> incrementReassemblyError(accepted.reason)
            is SecureMeshBleFragmentation.AcceptResult.Complete -> handleApplicationPacket(uuid, accepted.packet)
        }
    }

    private fun handleApplicationPacket(characteristicUuid: UUID, bytes: ByteArray) {
        val frame = codec.decodeApplicationPacket(bytes).getOrElse {
            incrementMalformed(it.message ?: "application packet decode failed")
            return
        }
        when (characteristicUuid) {
            config.responseCharacteristicUuid -> {
                val response = frame as? SecureMeshBleFrame.Response
                if (response == null || response.requestId == 0) {
                    incrementMalformed("RESPONSE characteristic carried non-response or requestId=0")
                    return
                }
                if (!requestManager.accept(response)) {
                    updateDiagnostics { it.copy(lastResponse = "unmatched response #${response.requestId} opcode=${response.rawOpcode}") }
                }
            }
            config.eventCharacteristicUuid -> {
                val event = frame as? SecureMeshBleFrame.Event
                if (event == null) {
                    incrementMalformed("EVENT characteristic carried non-event packet")
                    return
                }
                codec.parseEvent(event).onSuccess { decoded -> if (decoded != null) handleEvent(decoded) }
                    .onFailure { incrementMalformed("event ${event.rawOpcode}: ${it.message}") }
            }
        }
    }

    private fun handleEvent(event: BleDecodedEvent) {
        when (event) {
            is BleDecodedEvent.Node -> {
                addEvent(EventCategory.SYSTEM, event.type.name, "Node ${event.nodeId}", event.nodeId)
                scope.launch { syncNeighbors() }
            }
            is BleDecodedEvent.MessageQueued -> {
                addEvent(EventCategory.MESSAGES, "MESSAGE_QUEUED", "Message ${wireId(event.messageId)} → ${event.destination}, nextHop ${event.nextHop}", event.destination)
            }
            is BleDecodedEvent.HopAck -> {
                val id = wireId(event.messageId)
                _messages.value = _messages.value.map { message ->
                    if (message.id != id) message else {
                        val local = message.origin
                        val existing = message.hopTrace.firstOrNull { it.to == event.neighborId }
                        val hop = (existing ?: TransmissionHop(local, event.neighborId, null, HopAckState.PENDING, 0, null, null, now())).copy(
                            ackState = HopAckState.ACKED,
                            timestampEpochMs = now(),
                        )
                        message.copy(
                            progressState = MessageDeliveryState.HOP_PROGRESS,
                            // First-hop ACK is never an ordinary-message end-to-end receipt.
                            finalState = MessageFinalState.UNKNOWN,
                            hopTrace = message.hopTrace.filterNot { it.to == event.neighborId } + hop,
                        )
                    }
                }
                addEvent(EventCategory.MESSAGES, "HOP_ACK", "Message $id · first-hop ACK from ${event.neighborId}", event.neighborId)
            }
            is BleDecodedEvent.Retry -> {
                val id = wireId(event.messageId)
                _messages.value = _messages.value.map { message ->
                    if (message.id != id) message else {
                        val local = message.origin
                        val existing = message.hopTrace.firstOrNull { it.to == event.neighborId }
                        val hop = (existing ?: TransmissionHop(local, event.neighborId, null, HopAckState.PENDING, 0, null, null, now())).copy(
                            retries = event.attempt,
                            timestampEpochMs = now(),
                        )
                        message.copy(progressState = MessageDeliveryState.HOP_PROGRESS, finalState = MessageFinalState.UNKNOWN, hopTrace = message.hopTrace.filterNot { it.to == event.neighborId } + hop)
                    }
                }
                addEvent(EventCategory.MESSAGES, "RETRY", "Message $id · ${event.neighborId} · attempt ${event.attempt}", event.neighborId)
            }
            is BleDecodedEvent.LocalMessage -> {
                if (event.messageType == 1) {
                    val message = MeshMessage(
                        id = wireId(event.messageId),
                        origin = event.origin,
                        destination = event.destination,
                        payload = String(event.bytes, Charsets.UTF_8),
                        createdAtEpochMs = now(),
                        progressState = MessageDeliveryState.DELIVERED,
                        finalState = MessageFinalState.DELIVERED,
                        deliveredAtEpochMs = now(),
                    )
                    _messages.value = upsertMessage(_messages.value, message)
                }
                addEvent(EventCategory.MESSAGES, "MESSAGE_LOCAL_RECEIVED", "Message ${wireId(event.messageId)} from ${event.origin}", event.origin)
            }
            is BleDecodedEvent.RouteChanged -> {
                addEvent(EventCategory.ROUTING, "ROUTE_CHANGED", "${event.destination} via ${event.nextHop} · active=${event.active}", event.destination)
                scope.launch { syncRoutes() }
            }
            is BleDecodedEvent.TestStarted -> {
                addEvent(EventCategory.SYSTEM, "TEST_STARTED", "Test ${event.testId} → ${event.target}", event.target)
                scope.launch { syncFieldTestStatus() }
            }
            is BleDecodedEvent.TestPacketSent -> {
                _activeFieldTest.value = _activeFieldTest.value?.takeIf { it.id == event.testId.toString() }?.copy(
                    sent = event.sentCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    currentNextHop = event.firstNextHop,
                ) ?: _activeFieldTest.value
            }
            is BleDecodedEvent.TestPong -> {
                addEvent(EventCategory.SYSTEM, "TEST_PONG_RECEIVED", "Test ${event.testId} seq ${event.sequence} · RTT ${event.rttMs} ms")
            }
            is BleDecodedEvent.TestTimeout -> {
                addEvent(EventCategory.SYSTEM, "TEST_PACKET_TIMEOUT", "Test ${event.testId} seq ${event.sequence}")
            }
            is BleDecodedEvent.TestProgress -> {
                scope.launch { syncFieldTestStatus() }
            }
            is BleDecodedEvent.TestFinished -> {
                addEvent(EventCategory.SYSTEM, "TEST_FINISHED", "Test ${event.testId} · ${event.sent} sent · ${event.replies} E2E PONG · ${event.timeouts} timeout")
                scope.launch { syncFieldTestStatus() }
            }
            is BleDecodedEvent.RadioRecovery -> addEvent(EventCategory.RADIO, "RADIO_RECOVERY", "error=${event.errorCode} recovery=${event.recoveryCount}")
            is BleDecodedEvent.BleState -> {
                addEvent(EventCategory.SECURITY, "BLE_STATE", "state=${event.state}")
                updateDiagnostics { it.copy(gattState = if (event.state == BLE_STATE_PROTOCOL_READY) "PROTOCOL_READY" else it.gattState) }
            }
            is BleDecodedEvent.Error -> addEvent(EventCategory.SYSTEM, "ERROR", "context=${event.context} status=${event.status ?: event.rawStatus} related=${event.relatedId}")
            is BleDecodedEvent.NoReturnRoute -> addEvent(EventCategory.ROUTING, "NO_RETURN_ROUTE", "origin=${event.origin} test=${event.testId} seq=${event.sequence}", event.origin)
            is BleDecodedEvent.UiChanged -> {
                val session = _session.value
                if (session == null || event.state.localNodeId != session.localNodeIdentity.nodeId) {
                    incrementMalformed("UI_CHANGED local node mismatch")
                } else {
                    _deviceUiState.value = SecureMeshBleV01DomainMapping.deviceUiState(event.state, now())
                }
            }
        }
    }

    private fun requireReadySession(): SecureMeshSession {
        val session = _session.value ?: error("No SecureMesh session")
        require(session.authenticationState == AuthenticationState.AUTHENTICATED && handshakePhase == HandshakePhase.READY) { "SecureMesh session is not PROTOCOL_READY" }
        return session
    }

    private fun requireOk(response: SecureMeshBleFrame.Response) {
        val status = response.status ?: error("Unknown firmware status ${response.rawStatus}")
        require(status == BleCommandStatus.OK) { "SecureMesh command ${response.opcode ?: response.rawOpcode} failed: $status" }
    }

    private fun markProtocolUnavailable(technical: String) {
        val device = connectingDevice ?: return
        handshakePhase = HandshakePhase.IDLE
        _session.value = null
        _connectionState.value = MeshConnectionState.Connected(
            device = device,
            linkRssi = device.rssi,
            secureSession = SecureSessionState.NOT_CONFIGURED,
            protocolConfigured = false,
        )
        updateDiagnostics { it.copy(gattState = "PROTOCOL_UNAVAILABLE", secureSessionState = SecureSessionState.NOT_CONFIGURED, lastResponse = technical) }
    }

    private fun failAndClose(code: MeshErrorCode, technical: String, user: String) {
        requestManager.failAll(IllegalStateException(technical))
        _session.value = null
        resetProtocolState()
        closeGatt()
        fail(code, technical, user)
    }

    private fun fail(code: MeshErrorCode, technical: String, user: String) {
        _connectionState.value = MeshConnectionState.Error(MeshError(code, technical, user))
        updateDiagnostics { it.copy(gattState = "ERROR", lastResponse = technical, secureSessionState = SecureSessionState.NOT_AUTHENTICATED) }
    }

    private fun environmentState(): MeshConnectionState {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            return MeshConnectionState.BluetoothUnavailable
        }

        // Android 12+ protects several adapter operations with BLUETOOTH_CONNECT.
        // Check runtime permissions *before* touching adapter state. Some OEM stacks
        // (notably MIUI/HyperOS builds) enforce this more aggressively than others.
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) return MeshConnectionState.PermissionRequired(missing)

        val a = adapter ?: return MeshConnectionState.BluetoothUnavailable
        return try {
            if (a.isEnabled) MeshConnectionState.Idle else MeshConnectionState.BluetoothDisabled
        } catch (_: SecurityException) {
            MeshConnectionState.PermissionRequired(requiredPermissions())
        } catch (t: Throwable) {
            MeshConnectionState.Error(
                MeshError(
                    MeshErrorCode.BLUETOOTH_UNAVAILABLE,
                    "Bluetooth adapter state failed: ${t.message ?: t::class.java.simpleName}",
                    "Не удалось проверить состояние Bluetooth",
                )
            )
        }
    }

    private fun requiredPermissions(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun stopScanInternal(updateState: Boolean) {
        scanTimeoutJob?.cancel(); scanTimeoutJob = null
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        catch (_: SecurityException) {
            if (updateState) _connectionState.value = MeshConnectionState.PermissionRequired(requiredPermissions())
            return
        } catch (_: Throwable) { }
        if (updateState && (_connectionState.value is MeshConnectionState.Scanning || _connectionState.value is MeshConnectionState.DeviceFound)) {
            _connectionState.value = MeshConnectionState.Idle
            updateDiagnostics { it.copy(gattState = "IDLE") }
        }
    }

    private fun ensureBondReceiver() {
        if (bondReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) context.registerReceiver(bondReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            context.registerReceiver(bondReceiver, filter)
        }
        bondReceiverRegistered = true
    }

    private fun cancelConnectionJobs() {
        connectionTimeoutJob?.cancel(); connectionTimeoutJob = null
        disconnectTimeoutJob?.cancel(); disconnectTimeoutJob = null
        pairingTimeoutJob?.cancel(); pairingTimeoutJob = null
        mtuFallbackJob?.cancel(); mtuFallbackJob = null
    }

    private fun resetProtocolState() {
        _deviceUiState.value = null
        handshakePhase = HandshakePhase.IDLE
        secureService = null
        infoCharacteristic = null
        commandCharacteristic = null
        responseCharacteristic = null
        eventCharacteristic = null
        negotiatedMtu = 23
        responseSubscribed = false
        eventSubscribed = false
        infoReadAttempts = 0
        responseReassembler.reset()
        eventReassembler.reset()
        synchronized(stateLock) {
            writeAwaiter?.complete(BluetoothGatt.GATT_FAILURE)
            writeAwaiter = null
        }
    }

    private fun closeGatt() {
        try { gatt?.close() } catch (_: Throwable) { }
        gatt = null
    }

    private fun allocateTransportId(): Int = synchronized(stateLock) {
        val value = nextTransportId
        nextTransportId = if (nextTransportId == 0xFFFF) 1 else nextTransportId + 1
        value
    }

    private fun incrementMalformed(reason: String) {
        updateDiagnostics { it.copy(malformedPacketCount = it.malformedPacketCount + 1, lastResponse = "malformed: $reason") }
    }

    private fun incrementReassemblyError(reason: String) {
        updateDiagnostics { it.copy(reassemblyErrors = it.reassemblyErrors + 1, lastResponse = "reassembly: $reason") }
    }

    private fun updateDiagnostics(transform: (BleDiagnostics) -> BleDiagnostics) {
        _bleDiagnostics.value = transform(_bleDiagnostics.value ?: BleDiagnostics())
    }

    private fun addEvent(category: EventCategory, title: String, details: String, nodeId: NodeId? = null) {
        val event = MeshEvent("BLE-${now()}-${_events.value.size}", now(), category, title, details, nodeId)
        _events.value = (listOf(event) + _events.value).take(250)
    }

    private fun upsertMessage(items: List<MeshMessage>, message: MeshMessage): List<MeshMessage> =
        (items.filterNot { it.id == message.id } + message).sortedByDescending { it.createdAtEpochMs }.take(500)

    private fun wireId(value: Long): String = value.toString(16).uppercase().padStart(8, '0')

    private fun Int.toBondStatus(): BondStatus = when (this) {
        BluetoothDevice.BOND_BONDED -> BondStatus.BONDED
        BluetoothDevice.BOND_BONDING -> BondStatus.BONDING
        BluetoothDevice.BOND_NONE -> BondStatus.NOT_BONDED
        else -> BondStatus.UNKNOWN
    }

    companion object {
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val BLE_STATE_SECURE_LINK = 4
        private const val BLE_STATE_PROTOCOL_READY = 5
    }
}
