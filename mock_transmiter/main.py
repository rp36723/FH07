from __future__ import annotations

import argparse
import asyncio
import logging
import sys
import uuid
from pathlib import Path

PROJECT_ROOT = Path(__file__).parent
SRC_PATH = PROJECT_ROOT / "src"
if str(SRC_PATH) not in sys.path:
    sys.path.insert(0, str(SRC_PATH))

from bluetooth import BleStreamConfig, BleStreamer  # noqa: E402
from messages_builder import build_phone_packet, load_csv  # noqa: E402


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Mock IMU BLE transmitter")
    parser.add_argument(
        "--csv",
        required=True,
        type=Path,
        help="Path to CSV file containing IMU readings.",
    )
    parser.add_argument(
        "--service-uuid",
        type=uuid.UUID,
        default=BleStreamConfig().service_uuid,
        help="BLE service UUID to advertise.",
    )
    parser.add_argument(
        "--characteristic-uuid",
        type=uuid.UUID,
        default=BleStreamConfig().characteristic_uuid,
        help="BLE characteristic UUID used for notifications.",
    )
    parser.add_argument(
        "--interval",
        type=float,
        default=0.05,
        help="Seconds to wait between notification chunks.",
    )
    parser.add_argument(
        "--mtu",
        type=int,
        default=180,
        help="Chunk size in bytes for notifications (<= MTU).",
    )
    parser.add_argument(
        "--once",
        action="store_true",
        help="Send the CSV data once instead of looping indefinitely.",
    )
    parser.add_argument(
        "--log-level",
        default="INFO",
        choices=["DEBUG", "INFO", "WARN", "ERROR"],
        help="Logging verbosity.",
    )
    return parser.parse_args()


def configure_logging(level: str):
    logging.basicConfig(
        level=getattr(logging, level),
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    )


async def async_main(args: argparse.Namespace):
    if not args.csv.exists():
        raise FileNotFoundError(args.csv)

    readings = load_csv(args.csv)
    payload = build_phone_packet(readings)
    logging.info("Loaded %d readings (%d bytes FlatBuffer)", len(readings), len(payload))

    config = BleStreamConfig(
        service_uuid=args.service_uuid,
        characteristic_uuid=args.characteristic_uuid,
        mtu=args.mtu,
        interval_s=args.interval,
        loop_forever=not args.once,
    )
    streamer = BleStreamer(payload, config)
    await streamer.run()


def main():
    args = parse_args()
    configure_logging(args.log_level)
    try:
        asyncio.run(async_main(args))
    except KeyboardInterrupt:
        logging.info("Interrupted, shutting down.")


if __name__ == "__main__":
    main()
