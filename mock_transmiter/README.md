# Mock Transmitter

Mock utility for replaying recorded IMU data over Bluetooth Low Energy (BLE). It reads a CSV file of IMU samples, packs them into the `Phone_Packet` FlatBuffer defined in `sw/message_formats/device_link.fbs`, and advertises/serves the payload over BLE so a nearby listener can exercise the device-link pipeline without real hardware.

## Repository layout

- `main.py` – entry point; currently a stub, intended to load CSV, build the FlatBuffer, and push over BLE.
- `src/` – place transmitter logic and generated FlatBuffer Python modules.
- `sw/message_formats/` – shared FlatBuffer schemas (`device_link.fbs` + IMU includes).
- `.python-version` – pinned to Python 3.13.

## Prerequisites

- The `uv` python package manager.
- FlatBuffers compiler `flatc`
- A device that has bluetooth
- Python deps are managed via `uv sync` and include `bless` for cross‑platform BLE GATT server support.
- On Windows, several WinRT pre-release packages are pinned for Bless; `winrt-windows-storage-streams` is allowed at >=3.1 to satisfy bleak.

## Setup

```Bash
# all you should have to do is run uv sync to install everything then run from the repo root
uv sync
```

```bash
cd FH07
flatc --python -o mock_transmiter/src/messages -I sw/message_formats sw/message_formats/device_link.fbs
```

This emits Python modules for `Phone_Packet` and the IMU types into `mock_transmiter/src/messages` (mirroring the schema include paths). Regenerate any time the `.fbs` files change.

```bash
# then just do uv run
uv run main.py
```

## Expected CSV format

Each row should match the fields in `Positional_Readings`:

| sensor_id | timestamp_count | aclr_x | aclr_y | aclr_z | rot_x | rot_y | rot_z |
|-----------|-----------------|--------|--------|--------|-------|-------|-------|
| uint      | ulong           | uint   | uint   | uint   | uint  | uint  | uint  |

Example (`data/example.csv`):

```csv
sensor_id,timestamp_count,aclr_x,aclr_y,aclr_z,rot_x,rot_y,rot_z
1,123456789,120,118,130,2,1,0
1,123456889,121,119,129,2,1,0
```

`timestamp_count` maps to `Timestamp.count` (units are your capture clock ticks). Acceleration/rotation values are the raw integer representation expected by the consuming device.

## Planned transmit flow

1) Parse the CSV into `Positional_Readings` objects.
2) Build a `Phone_Packet` FlatBuffer containing the list of readings.
3) Expose the buffer over BLE (either as a GATT characteristic read/notify value or via advertisements, depending on receiver expectations).
4) Loop/repeat as needed to simulate a live stream.

Implementation notes:

- Use `flatbuffers.Builder` to pack the readings; store the final `builder.Output()` as the characteristic value.
- BLE MTU limits mean long payloads may need chunking or notifications; keep packets modest or implement fragmentation.
- Service/characteristic UUIDs are not defined yet—pick project-specific values and document them once chosen.

## TODOs

- Flesh out `main.py` with the CSV -> FlatBuffer -> BLE transmit pipeline.
- Provide a small sample CSV and a replay script in `src/`.
- Document the chosen BLE service/characteristic UUIDs once finalized.
