"""BLE peripheral helper for streaming FlatBuffer payloads.

Backends:
 - Windows: WinRT GATT server (winrt.* packages)
 - Linux/macOS: Bless (BlueZ/CoreBluetooth via bleak)
"""

from __future__ import annotations

import asyncio
import logging
import platform
import sys
import uuid
from dataclasses import dataclass
from typing import List

logger = logging.getLogger(__name__)

# Default UUIDs; override via CLI.
DEFAULT_SERVICE_UUID = uuid.UUID("12345678-1234-5678-1234-567812345678")
DEFAULT_CHAR_UUID = uuid.UUID("12345678-1234-5678-1234-567812345679")


def _chunk_bytes(data: bytes, mtu: int) -> List[bytes]:
    return [data[i : i + mtu] for i in range(0, len(data), mtu)] or [b""]


@dataclass
class BleStreamConfig:
    service_uuid: uuid.UUID = DEFAULT_SERVICE_UUID
    characteristic_uuid: uuid.UUID = DEFAULT_CHAR_UUID
    mtu: int = 180  # bytes per notification
    interval_s: float = 0.05
    loop_forever: bool = True
    device_name: str | None = None


# ---------------- Windows backend (WinRT) ---------------- #
if sys.platform == "win32":
    from winrt.windows.devices.bluetooth.genericattributeprofile import (
        GattCharacteristicProperties,
        GattLocalCharacteristic,
        GattLocalCharacteristicParameters,
        GattProtectionLevel,
        GattServiceProvider,
        GattServiceProviderAdvertisingParameters,
        GattServiceProviderResult,
    )
    from winrt.windows.storage.streams import DataWriter

    def _to_buffer(chunk: bytes):
        writer = DataWriter()
        writer.write_bytes(chunk)
        return writer.detach_buffer()

    class _WinRtBleStreamer:
        def __init__(self, payload: bytes, config: BleStreamConfig):
            self.payload = payload
            self.config = config
            self._provider: GattServiceProvider | None = None
            self._characteristic: GattLocalCharacteristic | None = None
            self._adv_started = asyncio.Event()
            self._stop_evt = asyncio.Event()
            self._chunk_bytes = _chunk_bytes(payload, config.mtu)
            self._chunk_buffers = [_to_buffer(c) for c in self._chunk_bytes]
            self._advertising = False

        async def start(self):
            res: GattServiceProviderResult = await GattServiceProvider.create_async(
                self.config.service_uuid
            )
            if res.error != 0:
                raise RuntimeError(f"GattServiceProvider.create_async failed with {res.error}")
            self._provider = res.service_provider

            char_params = GattLocalCharacteristicParameters()
            char_params.characteristic_properties = (
                GattCharacteristicProperties.READ | GattCharacteristicProperties.NOTIFY
            )
            char_params.read_protection_level = GattProtectionLevel.PLAIN
            char_params.write_protection_level = GattProtectionLevel.PLAIN
            char_params.user_description = "mock imu packet"
            if self.payload:
                char_params.static_value = _to_buffer(self._chunk_bytes[0])

            char_result = await self._provider.service.create_characteristic_async(
                self.config.characteristic_uuid, char_params
            )
            self._characteristic = char_result.characteristic
            self._characteristic.add_subscribed_clients_changed(self._on_subscribed_changed)

            adv_params = GattServiceProviderAdvertisingParameters()
            adv_params.is_connectable = True
            adv_params.is_discoverable = True
            self._provider.start_advertising_with_parameters(adv_params)
            self._advertising = True
            device_name = self.config.device_name or platform.node() or "<unknown device>"
            logger.info(
                "Advertising as '%s' with service %s characteristic %s",
                device_name,
                self.config.service_uuid,
                self.config.characteristic_uuid,
            )
            self._adv_started.set()

        async def stop(self):
            if self._provider and self._advertising:
                self._provider.stop_advertising()
                self._advertising = False
            self._stop_evt.set()

        async def wait_started(self):
            await self._adv_started.wait()

        def _on_subscribed_changed(self, sender, _args=None):
            subs = len(sender.subscribed_clients) if sender else 0
            logger.info("Subscribed clients changed: %d", subs)

        async def stream(self):
            if not self._characteristic:
                raise RuntimeError("Characteristic not initialized; call start() first")

            while not self._stop_evt.is_set():
                subscribers = list(self._characteristic.subscribed_clients)
                if not subscribers:
                    await asyncio.sleep(0.5)
                    continue

                for client in subscribers:
                    for chunk in self._chunk_buffers:
                        await self._characteristic.notify_value_for_subscribed_client_async(
                            chunk, client
                        )
                        await asyncio.sleep(self.config.interval_s)

                if not self.config.loop_forever:
                    break

        async def run(self):
            try:
                await self.start()
                await self.wait_started()
                await self.stream()
            finally:
                await self.stop()


# ---------------- Non-Windows backend (Bless) ---------------- #
else:
    try:
        from bless import (
            BlessServer,
            GATTAttributePermissions,
            GATTCharacteristicProperties,
        )
    except Exception as exc:  # pragma: no cover - import guard
        raise ImportError(
            "Bless backend requires the 'bless' package. "
            "Install dependencies (uv sync) on non-Windows platforms."
        ) from exc

    class _BlessBleStreamer:
        def __init__(self, payload: bytes, config: BleStreamConfig):
            self.payload = payload
            self.config = config
            self._server: BlessServer | None = None
            self._char_uuid_str = str(self.config.characteristic_uuid)
            self._svc_uuid_str = str(self.config.service_uuid)
            self._adv_started = asyncio.Event()
            self._stop_evt = asyncio.Event()
            self._chunks = _chunk_bytes(payload, config.mtu)

        async def start(self):
            loop = asyncio.get_running_loop()
            name = self.config.device_name or platform.node() or "mock-transmitter"
            self._server = BlessServer(name=name, loop=loop)
            self._server.read_request_func = self._handle_read
            self._server.write_request_func = self._handle_write

            await self._server.add_new_service(self._svc_uuid_str)
            char_flags = (
                GATTCharacteristicProperties.read | GATTCharacteristicProperties.notify
            )
            permissions = GATTAttributePermissions.readable
            await self._server.add_new_characteristic(
                self._svc_uuid_str,
                self._char_uuid_str,
                char_flags,
                None,
                permissions,
            )
            char = self._server.get_characteristic(self._char_uuid_str)
            char.value = self._chunks[0] if self._chunks else b""

            await self._server.start()
            logger.info(
                "Advertising as '%s' with service %s characteristic %s",
                name,
                self.config.service_uuid,
                self.config.characteristic_uuid,
            )
            self._adv_started.set()

        async def stop(self):
            if self._server:
                await self._server.stop()
            self._stop_evt.set()

        async def wait_started(self):
            await self._adv_started.wait()

        def _handle_read(self, characteristic, **kwargs):
            return characteristic.value

        def _handle_write(self, characteristic, value, **kwargs):
            characteristic.value = value

        async def stream(self):
            if not self._server:
                raise RuntimeError("Server not started; call start() first")

            while not self._stop_evt.is_set():
                if not await self._server.is_connected():
                    await asyncio.sleep(0.25)
                    continue

                for chunk in self._chunks:
                    char = self._server.get_characteristic(self._char_uuid_str)
                    char.value = chunk
                    self._server.update_value(self._svc_uuid_str, self._char_uuid_str)
                    await asyncio.sleep(self.config.interval_s)

                if not self.config.loop_forever:
                    break

        async def run(self):
            try:
                await self.start()
                await self.wait_started()
                await self.stream()
            finally:
                await self.stop()


# ---------------- Public facade ---------------- #
class BleStreamer:
    """Facade selecting the appropriate backend at runtime."""

    def __init__(self, payload: bytes, config: BleStreamConfig | None = None):
        if config is None:
            config = BleStreamConfig()
        if sys.platform == "win32":
            self._impl = _WinRtBleStreamer(payload, config)
        else:
            self._impl = _BlessBleStreamer(payload, config)

    async def start(self):
        await self._impl.start()

    async def stop(self):
        await self._impl.stop()

    async def wait_started(self):
        await self._impl.wait_started()

    async def stream(self):
        await self._impl.stream()

    async def run(self):
        await self._impl.run()
