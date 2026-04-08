#[cfg(not(target_os = "espidf"))]
fn main() {
    eprintln!(
        "The aggregator firmware builds for ESP-IDF targets only. Use `cargo check-aggregator` or `cargo run --bin aggregator --target xtensa-esp32s3-espidf`."
    );
}

#[cfg(target_os = "espidf")]
mod firmware {
    use std::sync::mpsc;
    use std::thread;
    use std::time::{Duration, Instant};

    use anyhow::Result;
    use ble_node::{
        GATT_SERVICE_UUID, ImuSample, NOTIFY_CHARACTERISTIC_UUID, PROTOCOL_VERSION,
        SAMPLE_PAYLOAD_LEN, STALE_TIMEOUT_MS, STATUS_CHARACTERISTIC_UUID, SensorTable,
        decode_manufacturer_data,
    };
    use esp_idf_svc::hal::task::block_on;
    use esp32_nimble::{
        BLEAdvertisementData, BLEDevice, BLEScan, NimbleProperties, utilities::BleUuid,
    };
    use log::{info, warn};
    use uuid::Uuid;

    pub fn run() -> Result<()> {
        esp_idf_svc::sys::link_patches();
        esp_idf_svc::log::EspLogger::initialize_default();

        let ble_device = BLEDevice::take();
        ble_device.set_preferred_mtu(64)?;

        let service_uuid = parse_uuid(GATT_SERVICE_UUID);
        let notify_uuid = parse_uuid(NOTIFY_CHARACTERISTIC_UUID);
        let status_uuid = parse_uuid(STATUS_CHARACTERISTIC_UUID);

        let advertising = ble_device.get_advertising();
        let server = ble_device.get_server();
        server.on_connect(|server, desc| {
            info!("phone connected: {:?}", desc);
            if let Err(err) = server.update_conn_params(desc.conn_handle(), 24, 48, 0, 60) {
                warn!("failed to update connection params: {err:?}");
            }
        });
        server.on_disconnect(|desc, reason| {
            info!("phone disconnected: {:?}, reason={:?}", desc, reason);
        });

        let service = server.create_service(service_uuid);
        let sample_stream = service.lock().create_characteristic(
            notify_uuid,
            NimbleProperties::READ | NimbleProperties::NOTIFY,
        );
        sample_stream.lock().set_value(&[0u8; SAMPLE_PAYLOAD_LEN]);

        let network_status = service
            .lock()
            .create_characteristic(status_uuid, NimbleProperties::READ);
        network_status
            .lock()
            .set_value(&[PROTOCOL_VERSION, 0, 0, 0, 0, 0]);

        let mut advertising_data = BLEAdvertisementData::new();
        advertising_data.name("BLE-Aggregator");
        advertising_data.add_service_uuid(service_uuid);
        advertising.lock().set_data(&mut advertising_data)?;
        advertising.lock().start()?;

        info!("aggregator GATT service is advertising");

        let (sample_tx, sample_rx) = mpsc::channel::<ImuSample>();
        let notifier_characteristic = sample_stream.clone();
        let status_characteristic = network_status.clone();
        let started_at = Instant::now();

        thread::spawn(move || {
            let mut table = SensorTable::new();

            loop {
                match sample_rx.recv_timeout(Duration::from_millis(20)) {
                    Ok(sample) => {
                        let now_ms = elapsed_ms(started_at);
                        table.ingest(sample, now_ms);

                        let payload = sample.encode();
                        {
                            let mut characteristic = notifier_characteristic.lock();
                            characteristic.set_value(&payload);
                            characteristic.notify();
                        }

                        let status = table.encode_network_status(
                            elapsed_ms(started_at),
                            now_ms,
                            STALE_TIMEOUT_MS,
                        );
                        status_characteristic.lock().set_value(&status);

                        info!(
                            "sample sensor={} seq={} ts_ms={} active_sensors={}",
                            sample.sensor_id,
                            sample.seq,
                            sample.timestamp_ms,
                            table.active_count(now_ms, STALE_TIMEOUT_MS)
                        );
                    }
                    Err(mpsc::RecvTimeoutError::Timeout) => {
                        let now_ms = elapsed_ms(started_at);
                        let removed = table.prune_stale(now_ms, STALE_TIMEOUT_MS);
                        if removed > 0 {
                            info!("removed {removed} stale sensor entries");
                        }

                        let status = table.encode_network_status(
                            elapsed_ms(started_at),
                            now_ms,
                            STALE_TIMEOUT_MS,
                        );
                        status_characteristic.lock().set_value(&status);
                    }
                    Err(mpsc::RecvTimeoutError::Disconnected) => break,
                }
            }
        });

        let mut scan = BLEScan::new();
        scan.active_scan(false)
            .filter_duplicates(false)
            .interval(75)
            .window(60);

        block_on(async move {
            loop {
                let tx = sample_tx.clone();
                scan.start(ble_device, 500, move |_device, data| {
                    if let Some(manufacturer) = data.manufacture_data()
                        && let Some(sample) = decode_manufacturer_data(
                            manufacturer.company_identifier,
                            manufacturer.payload,
                        )
                        && let Err(err) = tx.send(sample)
                    {
                        warn!("failed to forward scanned sample: {err}");
                    }

                    None::<()>
                })
                .await?;
            }
        })
    }

    fn elapsed_ms(started_at: Instant) -> u32 {
        started_at.elapsed().as_millis().min(u32::MAX as u128) as u32
    }

    fn parse_uuid(uuid: &str) -> BleUuid {
        BleUuid::from(Uuid::parse_str(uuid).expect("UUID constants must be valid"))
    }
}

#[cfg(target_os = "espidf")]
fn main() -> anyhow::Result<()> {
    firmware::run()
}
