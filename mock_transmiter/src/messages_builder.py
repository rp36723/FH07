from __future__ import annotations

import csv
import datetime as _dt
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, List

import flatbuffers

from messages import (
    Acceleration,
    Phone_Packet,
    Positional_Readings,
    Rotation,
    Sensor_ID,
    Timestamp,
)


@dataclass
class ImuReading:
    sensor_id: int
    timestamp_count: int
    aclr_x: int
    aclr_y: int
    aclr_z: int
    rot_x: int
    rot_y: int
    rot_z: int

    @classmethod
    def from_row(cls, row: dict) -> "ImuReading":
        return cls(
            sensor_id=int(row["sensor_id"]),
            timestamp_count=int(row["timestamp_count"]),
            aclr_x=int(row["aclr_x"]),
            aclr_y=int(row["aclr_y"]),
            aclr_z=int(row["aclr_z"]),
            rot_x=int(row["rot_x"]),
            rot_y=int(row["rot_y"]),
            rot_z=int(row["rot_z"]),
        )


REQUIRED_COLUMNS = [
    "sensor_id",
    "timestamp_count",
    "aclr_x",
    "aclr_y",
    "aclr_z",
    "rot_x",
    "rot_y",
    "rot_z",
]


def load_csv(path: Path) -> List[ImuReading]:
    with path.open(newline="") as f:
        reader = csv.DictReader(f)
        if not reader.fieldnames:
            raise ValueError("CSV has no header row")

        # Format 1: canonical schema columns.
        if set(REQUIRED_COLUMNS).issubset(reader.fieldnames):
            rows: List[ImuReading] = []
            for row in reader:
                if not row:
                    continue
                rows.append(ImuReading.from_row(row))
            return rows

        # Format 2: serial log style: timestamp,data with accel/gyro lines.
        if {"timestamp", "data"}.issubset(reader.fieldnames):
            return _parse_serial_log(reader)

        raise ValueError(
            f"Unrecognized CSV format; headers were {reader.fieldnames}. "
            "Expected either schema columns or timestamp/data format."
        )


def _parse_serial_log(reader: csv.DictReader) -> List[ImuReading]:
    """Parse 'timestamp,data' CSV where data lines are 'accel ...' or 'gyro ...'.

    Rules:
    - accel lines set pending acceleration (3 floats).
    - gyro lines emit a reading combining pending accel (or 1s if missing) and gyro values.
    - sensor_id defaults to 1; timestamp_count is ms since epoch parsed from timestamp.
    - any missing accel/gyro components are filled with 1.
    """

    def to_int(val: str) -> int:
        try:
            return int(float(val))
        except Exception:
            return 1

    def ts_to_count(ts_str: str) -> int:
        try:
            dt = _dt.datetime.fromisoformat(ts_str)
            return int(dt.timestamp() * 1000)
        except Exception:
            return 1

    readings: List[ImuReading] = []
    pending_accel = (1, 1, 1)

    for row in reader:
        data = (row.get("data") or "").strip()
        ts_count = ts_to_count(row.get("timestamp", ""))

        if data.startswith("accel"):
            parts = data.split()
            if len(parts) >= 4:
                pending_accel = tuple(to_int(v) for v in parts[1:4])
            else:
                pending_accel = (1, 1, 1)
        elif data.startswith("gyro"):
            parts = data.split()
            if len(parts) >= 4:
                rot = tuple(to_int(v) for v in parts[1:4])
            else:
                rot = (1, 1, 1)
            readings.append(
                ImuReading(
                    sensor_id=1,
                    timestamp_count=ts_count,
                    aclr_x=pending_accel[0],
                    aclr_y=pending_accel[1],
                    aclr_z=pending_accel[2],
                    rot_x=rot[0],
                    rot_y=rot[1],
                    rot_z=rot[2],
                )
            )
        else:
            # ignore temperature/other lines
            continue

    return readings


def build_phone_packet(readings: Iterable[ImuReading]) -> bytes:
    readings_list = list(readings)
    builder = flatbuffers.Builder(initialSize=1024 + 96 * len(readings_list))
    reading_offsets = []

    for reading in readings_list:
        Sensor_ID.Sensor_IDStart(builder)
        Sensor_ID.Sensor_IDAddId(builder, reading.sensor_id)
        sensor_id = Sensor_ID.Sensor_IDEnd(builder)

        Timestamp.TimestampStart(builder)
        Timestamp.TimestampAddCount(builder, reading.timestamp_count)
        timestamp = Timestamp.TimestampEnd(builder)

        Acceleration.AccelerationStart(builder)
        Acceleration.AccelerationAddVal(builder, reading.aclr_x)
        aclr_x = Acceleration.AccelerationEnd(builder)

        Acceleration.AccelerationStart(builder)
        Acceleration.AccelerationAddVal(builder, reading.aclr_y)
        aclr_y = Acceleration.AccelerationEnd(builder)

        Acceleration.AccelerationStart(builder)
        Acceleration.AccelerationAddVal(builder, reading.aclr_z)
        aclr_z = Acceleration.AccelerationEnd(builder)

        Rotation.RotationStart(builder)
        Rotation.RotationAddVal(builder, reading.rot_x)
        rot_x = Rotation.RotationEnd(builder)

        Rotation.RotationStart(builder)
        Rotation.RotationAddVal(builder, reading.rot_y)
        rot_y = Rotation.RotationEnd(builder)

        Rotation.RotationStart(builder)
        Rotation.RotationAddVal(builder, reading.rot_z)
        rot_z = Rotation.RotationEnd(builder)

        Positional_Readings.Positional_ReadingsStart(builder)
        Positional_Readings.Positional_ReadingsAddId(builder, sensor_id)
        Positional_Readings.Positional_ReadingsAddTime(builder, timestamp)
        Positional_Readings.Positional_ReadingsAddAclrX(builder, aclr_x)
        Positional_Readings.Positional_ReadingsAddAclrY(builder, aclr_y)
        Positional_Readings.Positional_ReadingsAddAclrZ(builder, aclr_z)
        Positional_Readings.Positional_ReadingsAddRotX(builder, rot_x)
        Positional_Readings.Positional_ReadingsAddRotY(builder, rot_y)
        Positional_Readings.Positional_ReadingsAddRotZ(builder, rot_z)
        reading_offsets.append(Positional_Readings.Positional_ReadingsEnd(builder))

    Phone_Packet.Phone_PacketStartReadingsVector(builder, len(reading_offsets))
    for offset in reversed(reading_offsets):
        builder.PrependUOffsetTRelative(offset)
    readings_vec = builder.EndVector()

    Phone_Packet.Phone_PacketStart(builder)
    Phone_Packet.Phone_PacketAddReadings(builder, readings_vec)
    packet = Phone_Packet.Phone_PacketEnd(builder)
    builder.Finish(packet)
    return bytes(builder.Output())
