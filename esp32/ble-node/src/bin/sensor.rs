#[cfg(not(target_os = "espidf"))]
fn main() {
    eprintln!(
        "The sensor firmware builds for ESP-IDF targets only. Use `cargo check-sensor` or `cargo run --bin sensor --target xtensa-esp32s3-espidf`."
    );
}

#[cfg(target_os = "espidf")]
mod firmware {
    use std::time::Instant;

    use anyhow::Result;
    use ble_node::{
        ImuSource, MockImuSource, SensorId, advertisement_bytes, sensor_id_from_mac_be,
    };
    use esp_idf_svc::hal::delay::FreeRtos;
    use esp32_nimble::{BLEDevice, enums::ConnMode};
    use log::info;

    pub fn run() -> Result<()> {
        esp_idf_svc::sys::link_patches();
        esp_idf_svc::log::EspLogger::initialize_default();

        let ble_device = BLEDevice::take();
        let sensor_id = resolve_sensor_id(ble_device)?;
        let mut imu = MockImuSource::new(sensor_id);
        let boot = Instant::now();

        let advertising = ble_device.get_advertising();
        {
            let mut advertising = advertising.lock();
            advertising
                .advertisement_type(ConnMode::Non)
                .scan_response(false)
                .min_interval(160)
                .max_interval(160);
        }

        info!("sensor role started with sensor_id={sensor_id}");

        let mut seq = 0u16;
        loop {
            let elapsed_ms = boot.elapsed().as_millis().min(u32::MAX as u128) as u32;
            let sample = imu.next_sample(sensor_id, seq, elapsed_ms);

            if seq % 5 == 0 {
                let payload = advertisement_bytes(sample);
                let mut advertising = advertising.lock();
                if advertising.is_advertising() {
                    let _ = advertising.stop();
                }
                advertising.set_raw_data(&payload)?;
                advertising.start()?;

                info!(
                    "advertising sensor={} seq={} ts_ms={} ax={} ay={} az={} gx={} gy={} gz={}",
                    sample.sensor_id,
                    sample.seq,
                    sample.timestamp_ms,
                    sample.ax,
                    sample.ay,
                    sample.az,
                    sample.gx,
                    sample.gy,
                    sample.gz
                );
            }

            seq = seq.wrapping_add(1);
            FreeRtos::delay_ms(20);
        }
    }

    fn resolve_sensor_id(ble_device: &BLEDevice) -> Result<SensorId> {
        if let Some(explicit) =
            option_env!("BLE_SENSOR_ID").and_then(|value| value.parse::<u8>().ok())
        {
            return Ok(explicit);
        }

        let address = ble_device.get_addr()?;
        Ok(sensor_id_from_mac_be(address.as_be_bytes()))
    }
}

#[cfg(target_os = "espidf")]
fn main() -> anyhow::Result<()> {
    firmware::run()
}
