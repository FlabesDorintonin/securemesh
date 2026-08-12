from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BLE = ROOT / "app/src/main/java/dev/securemesh/commander/data/ble/BleTransport.kt"
DISCOVERY = ROOT / "app/src/main/java/dev/securemesh/commander/feature/discovery/DiscoveryScreen.kt"


def replace_once(path: Path, old: str, new: str) -> bool:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return False
    if old not in text:
        raise SystemExit(f"expected source block not found in {path}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    return True


changed = False

changed |= replace_once(
    BLE,
    '''    private suspend fun beginSystemBonding(device: BluetoothDevice) {
        val uiDevice = connectingDevice ?: return
        updateDiagnostics { it.copy(gattState = "SERVICE_VERIFIED", bonded = device.bondState == BluetoothDevice.BOND_BONDED) }
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> continueAfterBond()
            BluetoothDevice.BOND_BONDING -> waitForBond(uiDevice)
            else -> {
                waitForBond(uiDevice)
                val started = try { device.createBond() } catch (_: SecurityException) { false }
                if (!started) failAndClose(MeshErrorCode.PAIRING_FAILED, "BluetoothDevice.createBond returned false", "Android не смог начать системное сопряжение")
            }
        }
    }
''',
    '''    private suspend fun beginSystemBonding(device: BluetoothDevice) {
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
''',
)

changed |= replace_once(
    BLE,
    '''    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
            } ?: return
            if (device.address != connectingDevice?.address) return
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
                    failAndClose(MeshErrorCode.PAIRING_FAILED, "System BLE bonding failed", "Сопряжение SecureMesh не подтверждено")
                }
            }
        }
    }
''',
    '''    private val bondReceiver = object : BroadcastReceiver() {
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
''',
)

changed |= replace_once(
    BLE,
    '''    private fun ensureBondReceiver() {
        if (bondReceiverRegistered) return
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
''',
    '''    private fun ensureBondReceiver() {
        if (bondReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
        }
''',
)

changed |= replace_once(
    BLE,
    '''        if (status != BluetoothGatt.GATT_SUCCESS) {
            failAndClose(
                if (status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION || status == BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION) MeshErrorCode.PAIRING_FAILED else MeshErrorCode.PROTOCOL_MISMATCH,
                "INFO GATT read status $status",
                "SecureMesh INFO недоступен после сопряжения",
            )
            return
        }
''',
    '''        if (status != BluetoothGatt.GATT_SUCCESS) {
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
''',
)

changed |= replace_once(
    DISCOVERY,
    '''        Text(
            "Сверь 6-значный код на OLED узла с системным окном Android и подтверди сопряжение.",
            color = SecureMeshColors.TextSecondary,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = SecureMeshColors.CyanHot)
            Text("Код обрабатывает Android — приложение его не хранит и не отправляет через COMMAND.", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
''',
    '''        Text(
            "На OLED узла появится случайный 6-значный CODE. Android откроет системное окно ввода PIN/passkey — введи туда код с OLED.",
            color = SecureMeshColors.TextSecondary,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = SecureMeshColors.CyanHot)
            Text("Ввод выполняет системный Bluetooth Android. SecureMesh не хранит passkey и не отправляет его через COMMAND.", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
''',
)

print("pairing hotfix applied" if changed else "pairing hotfix already present")
