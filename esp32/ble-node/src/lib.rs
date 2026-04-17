#[cfg(target_os = "espidf")]
pub mod hardware_imu;
pub mod imu;
pub mod mock_imu;
pub mod protocol;
pub mod sensor_table;

#[cfg(target_os = "espidf")]
pub use hardware_imu::HardwareImuSource;
pub use imu::{DetectedImu, ImuBusConfig, ImuSensorConfig, ImuSource, RequestedImu};
pub use mock_imu::MockImuSource;
pub use protocol::{
    ADV_FLAGS, COMPANY_IDENTIFIER, GATT_SERVICE_UUID, ImuSample, NOTIFY_CHARACTERISTIC_UUID,
    PROTOCOL_VERSION, SAMPLE_PAYLOAD_LEN, STATUS_CHARACTERISTIC_UUID, SensorId,
    advertisement_bytes, decode_manufacturer_data, encode_manufacturer_data, sensor_id_from_mac_be,
};
pub use sensor_table::{
    MAX_SENSORS, NETWORK_STATUS_ENTRY_LEN, NETWORK_STATUS_HEADER_LEN, STALE_TIMEOUT_MS, SensorTable,
};
