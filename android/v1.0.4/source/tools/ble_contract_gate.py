#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID_CODEC = ROOT / 'app/src/main/java/dev/securemesh/commander/data/ble/SecureMeshBleCodec.kt'
ANDROID_CONFIG = ROOT / 'app/src/main/java/dev/securemesh/commander/data/ble/BleProtocolConfig.kt'
ANDROID_FRAGMENT = ROOT / 'app/src/main/java/dev/securemesh/commander/data/ble/BleFragmentation.kt'
ANDROID_MAPPING = ROOT / 'app/src/main/java/dev/securemesh/commander/data/ble/BleDomainMapping.kt'

EXPECTED_COMMANDS = {
    'GET_INFO': 1, 'GET_STATUS': 2, 'GET_NEIGHBORS': 3, 'GET_ROUTES': 4, 'SEND_MESSAGE': 5,
    'ADD_STATIC_ROUTE': 6, 'REMOVE_STATIC_ROUTE': 7, 'START_FIELD_TEST': 8, 'STOP_FIELD_TEST': 9,
    'GET_FIELD_TEST_STATUS': 10, 'PING_LOCAL': 11, 'CLEAR_STATS': 12, 'GET_UI_STATE': 13,
    'UI_ACTION': 14, 'GET_KNOWN_NODES': 15, 'GET_MANIFEST': 16, 'SET_MANIFEST': 17,
    'DISCOVER_ROUTE': 18, 'GET_ROUTING_DIAGNOSTICS': 19, 'INJECT_LINK_FAILURE': 20,
    'CLEAR_DYNAMIC_ROUTES': 21, 'SET_LAB_LINK_POLICY': 22, 'GET_LAB_LINK_POLICIES': 23,
    'GET_POSITIONS': 24, 'RAISE_SOS': 25, 'ACK_SOS': 26, 'SEND_COMMAND_NOTICE': 27,
    'GET_BLE_RADAR': 28, 'CLEAR_BLE_RADAR': 29, 'GET_OPERATIONAL_HEALTH': 30,
    'GET_SELF_DIAGNOSTICS': 31,
}
EXPECTED_EVENTS = {
    'NODE_DISCOVERED': 1, 'NODE_STALE': 2, 'MESSAGE_QUEUED': 3, 'HOP_ACK': 4, 'RETRY': 5,
    'MESSAGE_LOCAL_RECEIVED': 6, 'ROUTE_CHANGED': 7, 'TEST_STARTED': 8, 'TEST_PACKET_SENT': 9,
    'TEST_PONG_RECEIVED': 10, 'TEST_PACKET_TIMEOUT': 11, 'TEST_PROGRESS': 12, 'TEST_FINISHED': 13,
    'RADIO_RECOVERY': 14, 'BLE_STATE': 15, 'ERROR': 16, 'NO_RETURN_ROUTE': 17, 'UI_CHANGED': 18,
    'ROUTE_DISCOVERY_STARTED': 19, 'ROUTE_DISCOVERY_RETRY': 20, 'ROUTE_READY': 21, 'G2_READY': 22,
    'G2_UNAVAILABLE': 23, 'ROUTE_PROMOTED': 24, 'ROUTE_LOST': 25, 'MANIFEST_CHANGED': 26,
    'KNOWN_NODE_ADDED': 27, 'POSITION_UPDATED': 28, 'SOS_RAISED': 29, 'SOS_ACKNOWLEDGED': 30,
    'COMMAND_NOTICE_RECEIVED': 31, 'OPERATIONAL_HEALTH_CHANGED': 32,
}
EXPECTED_STATUS = {
    'OK': 0, 'INVALID_COMMAND': 1, 'INVALID_ARGUMENT': 2, 'NOT_AUTHENTICATED': 3,
    'NOT_SUPPORTED': 4, 'BUSY': 5, 'NO_ROUTE': 6, 'TX_QUEUE_FULL': 7,
    'RADIO_UNAVAILABLE': 8, 'CRYPTO_UNAVAILABLE': 9, 'TEST_ALREADY_RUNNING': 10,
    'TEST_NOT_RUNNING': 11, 'TIMEOUT': 12, 'INTERNAL_ERROR': 13,
}
EXPECTED_UUIDS = {
    'serviceUuid': '7b7f0001-6b6f-4d65-7368-534543555245',
    'infoCharacteristicUuid': '7b7f0002-6b6f-4d65-7368-534543555245',
    'commandCharacteristicUuid': '7b7f0003-6b6f-4d65-7368-534543555245',
    'responseCharacteristicUuid': '7b7f0004-6b6f-4d65-7368-534543555245',
    'eventCharacteristicUuid': '7b7f0005-6b6f-4d65-7368-534543555245',
}
EXPECTED_CAP_BITS = {
    'MESSAGING': 0, 'STATIC_ROUTING': 1, 'RELAY': 2, 'FIELD_TEST': 3, 'BLE_CONTROL': 4,
    'UI_OS': 5, 'VANGUARD': 6, 'MANIFEST': 7, 'FAULT_LAB': 8, 'GPS': 9, 'SOS': 10,
    'COMMAND_MAP': 11, 'BLE_RADAR': 12, 'OPERATIONAL_HEALTH': 13, 'SELF_DIAGNOSTICS': 14,
}

failures: list[str] = []
passes = 0

def ok(name: str, cond: bool, detail: str = '') -> None:
    global passes
    if cond:
        passes += 1
        print(f'PASS  {name}')
    else:
        failures.append(f'{name}: {detail}'.rstrip(': '))
        print(f'FAIL  {name}' + (f' — {detail}' if detail else ''))


def camel_to_snake(value: str) -> str:
    value = re.sub(r'([a-z0-9])([A-Z])', r'\1_\2', value)
    value = re.sub(r'([A-Z]+)([A-Z][a-z])', r'\1_\2', value)
    return value.upper()


def enum_block(text: str, start_pattern: str) -> str:
    m = re.search(start_pattern + r'\s*\{(?P<body>.*?)\};', text, re.S)
    if not m:
        raise ValueError(f'enum not found: {start_pattern}')
    return m.group('body')


def parse_cpp_enum(body: str, prefix: str = '') -> dict[str, int]:
    out: dict[str, int] = {}
    for name, value in re.findall(r'\b([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(\d+)', body):
        key = name[len(prefix):] if prefix and name.startswith(prefix) else name
        out[camel_to_snake(key)] = int(value)
    return out


def parse_kotlin_wire_enum(text: str, enum_name: str) -> dict[str, int]:
    m = re.search(r'enum class\s+' + re.escape(enum_name) + r'\(val wire: Int\)\s*\{(?P<body>.*?)\n\s*companion object', text, re.S)
    if not m:
        raise ValueError(f'Kotlin enum not found: {enum_name}')
    return {name: int(value) for name, value in re.findall(r'\b([A-Z][A-Z0-9_]*)\((\d+)\)', m.group('body'))}


def cpp_const_int(text: str, name: str) -> int | None:
    m = re.search(r'\b' + re.escape(name) + r'\s*=\s*(0x[0-9A-Fa-f]+|\d+)', text)
    return int(m.group(1), 0) if m else None


def kotlin_const_int(text: str, name: str) -> int | None:
    m = re.search(r'\bconst val\s+' + re.escape(name) + r'\s*=\s*(0x[0-9A-Fa-f]+|[\d_]+)', text)
    return int(m.group(1).replace('_', ''), 0) if m else None


def main() -> int:
    parser = argparse.ArgumentParser(description='Cross-check SecureMesh Android BLE contract against exact firmware v1.0.4 source.')
    parser.add_argument('--firmware', type=Path, required=True, help='Path to SecureMesh_v1_0_4_OPERATOR.ino')
    args = parser.parse_args()

    firmware_path = args.firmware.resolve()
    for path in (firmware_path, ANDROID_CODEC, ANDROID_CONFIG, ANDROID_FRAGMENT, ANDROID_MAPPING):
        if not path.is_file():
            print(f'ERROR missing required source: {path}', file=sys.stderr)
            return 2

    fw = firmware_path.read_text(encoding='utf-8')
    codec = ANDROID_CODEC.read_text(encoding='utf-8')
    config = ANDROID_CONFIG.read_text(encoding='utf-8')
    fragment = ANDROID_FRAGMENT.read_text(encoding='utf-8')
    mapping = ANDROID_MAPPING.read_text(encoding='utf-8')

    fw_all_commands = parse_cpp_enum(enum_block(fw, r'enum class\s+CommandType\s*:\s*uint8_t'))
    fw_commands = {name: wire for name, wire in fw_all_commands.items() if wire <= 31}
    android_commands = parse_kotlin_wire_enum(codec, 'BleOpcode')
    ok('Firmware BLE application command map equals canonical v1.0.4', fw_commands == EXPECTED_COMMANDS, repr(fw_commands))
    ok('Android command map equals canonical v1.0.4', android_commands == EXPECTED_COMMANDS, repr(android_commands))
    ok('Firmware and Android BLE application command maps are identical', fw_commands == android_commands)
    maintenance = {name: wire for name, wire in fw_all_commands.items() if wire >= 32}
    ok('Firmware maintenance opcodes 32+ remain outside Android app API', maintenance == {'BLE_STATUS': 32, 'BLE_ADVERTISE': 33, 'BLE_BONDS': 34, 'BLE_BONDS_CLEAR': 35, 'BROADCAST': 36, 'REBOOT': 37} and 'rawCommand >= static_cast<uint8_t>(CommandType::BleStatus)' in fw and 'result.status = CommandStatus::NotSupported' in fw, repr(maintenance))

    fw_events = parse_cpp_enum(enum_block(fw, r'enum\s+BleEventType\s*:\s*uint8_t'), 'EVT_')
    android_events = parse_kotlin_wire_enum(codec, 'BleEventType')
    ok('Firmware event map equals canonical v1.0.4', fw_events == EXPECTED_EVENTS, repr(fw_events))
    ok('Android event map equals canonical v1.0.4', android_events == EXPECTED_EVENTS, repr(android_events))
    ok('Firmware and Android event maps are identical', fw_events == android_events)

    fw_status = parse_cpp_enum(enum_block(fw, r'enum class\s+CommandStatus\s*:\s*uint8_t'))
    android_status = parse_kotlin_wire_enum(codec, 'BleCommandStatus')
    ok('Firmware command status map is canonical', fw_status == EXPECTED_STATUS, repr(fw_status))
    ok('Android command status map is canonical', android_status == EXPECTED_STATUS, repr(android_status))
    ok('Firmware and Android status maps are identical', fw_status == android_status)

    # App protocol v2 and fragment transport v1 are deliberately separate contracts.
    pairs = [
        ('application magic', cpp_const_int(fw, 'BLE_PROTOCOL_MAGIC'), kotlin_const_int(codec, 'MAGIC'), 0x4D53),
        ('application version', cpp_const_int(fw, 'BLE_PROTOCOL_VERSION'), kotlin_const_int(codec, 'VERSION'), 2),
        ('application header', cpp_const_int(fw, 'BLE_APP_HEADER_SIZE'), kotlin_const_int(codec, 'HEADER_SIZE'), 10),
        ('application max packet', cpp_const_int(fw, 'BLE_MAX_APP_PACKET'), kotlin_const_int(codec, 'MAX_PACKET_SIZE'), 384),
        ('fragment magic', cpp_const_int(fw, 'BLE_FRAGMENT_MAGIC'), kotlin_const_int(fragment, 'MAGIC'), 0x4653),
        ('fragment version', cpp_const_int(fw, 'BLE_FRAGMENT_VERSION'), kotlin_const_int(fragment, 'VERSION'), 1),
        ('fragment header', cpp_const_int(fw, 'BLE_FRAGMENT_HEADER_SIZE'), kotlin_const_int(fragment, 'HEADER_SIZE'), 12),
        ('fragment max data', cpp_const_int(fw, 'BLE_MAX_FRAGMENT_DATA'), kotlin_const_int(fragment, 'MAX_FRAGMENT_DATA'), 180),
        ('fragment max count', cpp_const_int(fw, 'BLE_MAX_FRAGMENTS'), kotlin_const_int(fragment, 'MAX_FRAGMENT_COUNT'), 48),
        ('fragment max application', cpp_const_int(fw, 'BLE_MAX_APP_PACKET'), kotlin_const_int(fragment, 'MAX_APPLICATION_PACKET'), 384),
    ]
    for name, a, b, expected in pairs:
        ok(f'{name}: firmware={expected}', a == expected, str(a))
        ok(f'{name}: Android={expected}', b == expected, str(b))
        ok(f'{name}: cross-contract match', a == b, f'{a} != {b}')

    for property_name, uuid in EXPECTED_UUIDS.items():
        ok(f'Android {property_name} UUID', uuid in config)
        ok(f'Firmware UUID {uuid[:8]}', uuid in fw)

    # Wire payload sizes introduced by firmware v1.0.4.
    ok('Firmware operational health payload=17', cpp_const_int(fw, 'BLE_OPERATIONAL_HEALTH_PAYLOAD_BYTES') == 17)
    ok('Firmware self diagnostics payload=43', cpp_const_int(fw, 'BLE_SELF_DIAG_PAYLOAD_BYTES') == 43)
    ok('Firmware BLE radar header=12', cpp_const_int(fw, 'BLE_RADAR_HEADER_BYTES') == 12)
    ok('Firmware BLE radar record=30', cpp_const_int(fw, 'BLE_RADAR_RECORD_BYTES') == 30)
    ok('Android operational health parser expects 17', 'BleOpcode.GET_OPERATIONAL_HEALTH, 17' in codec)
    ok('Android self diagnostics parser expects 43', 'BleOpcode.GET_SELF_DIAGNOSTICS, 43' in codec)
    ok('Android radar parser uses 12/30 constants', 'BLE_RADAR_HEADER_SIZE = 12' in codec and 'BLE_RADAR_RECORD_SIZE = 30' in codec)

    # Capability bits must remain stable. Command Map is intentionally projected onto messaging UI.
    for cap, bit in EXPECTED_CAP_BITS.items():
        fw_name = f'CAP_{cap}'
        fw_match = re.search(r'\b' + re.escape(fw_name) + r'\s*=\s*1UL\s*<<\s*(\d+)', fw)
        ok(f'Firmware capability {cap}=bit{bit}', fw_match is not None and int(fw_match.group(1)) == bit)
        if cap == 'COMMAND_MAP':
            ok('Android Command Map bit11 is explicitly projected', 'mask and (1L shl 11)' in mapping and 'Command Map rides' in mapping)
        else:
            ok(f'Android maps capability bit{bit}', f'mask and (1L shl {bit})' in mapping)

    # Historical 0.9.3 assignments must never leak into v1.0.4.
    forbidden = ['FIND_DEVICE(28)', 'RUN_SELF_TEST(29)', 'GET_HEALTH(28)', 'FIND_DEVICE(29)', 'GET_SECURITY_STATUS(30)', 'GET_MAILBOX(31)']
    ok('No historical conflicting 0.9.3 opcodes in Android codec', not any(item in codec for item in forbidden))

    print(f'\n{passes} passed, {len(failures)} failed')
    if failures:
        for failure in failures:
            print(f' - {failure}')
        return 1
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
