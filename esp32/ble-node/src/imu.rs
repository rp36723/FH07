use crate::protocol::SensorId;

pub trait ImuSource {
    fn next_sample(
        &mut self,
        sensor_id: SensorId,
        seq: u16,
        timestamp_ms: u32,
    ) -> crate::protocol::ImuSample;
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RequestedImu {
    Mock,
    Auto,
    Mpu6050,
    Mpu65x0,
}

impl RequestedImu {
    pub fn parse(value: &str) -> Option<Self> {
        let normalized = value.trim().to_ascii_lowercase();
        match normalized.as_str() {
            "mock" => Some(Self::Mock),
            "auto" => Some(Self::Auto),
            "mpu6050" | "mpu-6050" => Some(Self::Mpu6050),
            "mpu6500" | "mpu-6500" | "mpu9250" | "mpu-9250" | "mpu9255" | "mpu-9255"
            | "mpu65x0" | "mpu-65x0" | "mpu925x" | "mpu-925x" => Some(Self::Mpu65x0),
            _ => None,
        }
    }

    pub fn matches(self, detected: DetectedImu) -> bool {
        match self {
            Self::Mock | Self::Auto => true,
            Self::Mpu6050 => detected == DetectedImu::Mpu6050,
            Self::Mpu65x0 => detected.is_mpu65x0_family(),
        }
    }

    pub fn supported_values() -> &'static str {
        "mock, auto, mpu6050, mpu6500, mpu9250, mpu9255, mpu65x0"
    }

    pub fn label(self) -> &'static str {
        match self {
            Self::Mock => "mock",
            Self::Auto => "auto",
            Self::Mpu6050 => "MPU-6050",
            Self::Mpu65x0 => "MPU-65x0 family",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DetectedImu {
    Mpu6050,
    Mpu6500,
    Mpu9250,
    Mpu9255,
}

impl DetectedImu {
    pub fn from_who_am_i(value: u8) -> Option<Self> {
        match value {
            0x68 => Some(Self::Mpu6050),
            0x70 => Some(Self::Mpu6500),
            0x71 => Some(Self::Mpu9250),
            0x73 => Some(Self::Mpu9255),
            _ => None,
        }
    }

    pub fn is_mpu65x0_family(self) -> bool {
        matches!(self, Self::Mpu6500 | Self::Mpu9250 | Self::Mpu9255)
    }

    pub fn label(self) -> &'static str {
        match self {
            Self::Mpu6050 => "MPU-6050",
            Self::Mpu6500 => "MPU-6500",
            Self::Mpu9250 => "MPU-9250",
            Self::Mpu9255 => "MPU-9255",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ImuBusConfig {
    pub sda_pin: u8,
    pub scl_pin: u8,
    pub address: u8,
    pub baudrate_hz: u32,
}

impl Default for ImuBusConfig {
    fn default() -> Self {
        Self {
            sda_pin: 1,
            scl_pin: 2,
            address: 0x68,
            baudrate_hz: 400_000,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ImuSensorConfig {
    pub requested: RequestedImu,
    pub bus: ImuBusConfig,
}

impl Default for ImuSensorConfig {
    fn default() -> Self {
        Self {
            requested: RequestedImu::Mpu6050,
            bus: ImuBusConfig::default(),
        }
    }
}

impl ImuSensorConfig {
    pub fn from_compile_env() -> Result<Self, String> {
        Self::from_env_values(
            option_env!("BLE_IMU_BOARD"),
            option_env!("BLE_IMU_SDA"),
            option_env!("BLE_IMU_SCL"),
            option_env!("BLE_IMU_ADDR"),
            option_env!("BLE_IMU_I2C_HZ"),
        )
    }

    pub fn from_env_values(
        requested: Option<&str>,
        sda_pin: Option<&str>,
        scl_pin: Option<&str>,
        address: Option<&str>,
        baudrate_hz: Option<&str>,
    ) -> Result<Self, String> {
        let defaults = Self::default();
        let requested = requested
            .map(parse_requested_imu)
            .transpose()?
            .unwrap_or(defaults.requested);

        let mut bus = defaults.bus;
        if let Some(sda_pin) = sda_pin {
            bus.sda_pin = parse_u8("BLE_IMU_SDA", sda_pin)?;
        }
        if let Some(scl_pin) = scl_pin {
            bus.scl_pin = parse_u8("BLE_IMU_SCL", scl_pin)?;
        }
        if let Some(address) = address {
            bus.address = parse_u8("BLE_IMU_ADDR", address)?;
        }
        if let Some(baudrate_hz) = baudrate_hz {
            bus.baudrate_hz = parse_u32("BLE_IMU_I2C_HZ", baudrate_hz)?;
        }

        Ok(Self { requested, bus })
    }
}

fn parse_requested_imu(value: &str) -> Result<RequestedImu, String> {
    RequestedImu::parse(value).ok_or_else(|| {
        format!(
            "BLE_IMU_BOARD must be one of: {}",
            RequestedImu::supported_values()
        )
    })
}

fn parse_u8(name: &str, value: &str) -> Result<u8, String> {
    let trimmed = value.trim();
    if let Some(hex) = trimmed
        .strip_prefix("0x")
        .or_else(|| trimmed.strip_prefix("0X"))
    {
        u8::from_str_radix(hex, 16).map_err(|_| format!("{name} must be a valid u8 value"))
    } else {
        trimmed
            .parse::<u8>()
            .map_err(|_| format!("{name} must be a valid u8 value"))
    }
}

fn parse_u32(name: &str, value: &str) -> Result<u32, String> {
    value
        .trim()
        .parse::<u32>()
        .map_err(|_| format!("{name} must be a valid u32 value"))
}

#[cfg(test)]
mod tests {
    use super::{DetectedImu, ImuSensorConfig, RequestedImu};

    #[test]
    fn requested_imu_aliases_parse() {
        assert_eq!(RequestedImu::parse("mock"), Some(RequestedImu::Mock));
        assert_eq!(RequestedImu::parse("MPU-6050"), Some(RequestedImu::Mpu6050));
        assert_eq!(RequestedImu::parse("mpu9250"), Some(RequestedImu::Mpu65x0));
        assert_eq!(RequestedImu::parse("mpu-9255"), Some(RequestedImu::Mpu65x0));
        assert_eq!(RequestedImu::parse("mpu65x0"), Some(RequestedImu::Mpu65x0));
    }

    #[test]
    fn who_am_i_values_detect_supported_parts() {
        assert_eq!(DetectedImu::from_who_am_i(0x68), Some(DetectedImu::Mpu6050));
        assert_eq!(DetectedImu::from_who_am_i(0x70), Some(DetectedImu::Mpu6500));
        assert_eq!(DetectedImu::from_who_am_i(0x71), Some(DetectedImu::Mpu9250));
        assert_eq!(DetectedImu::from_who_am_i(0x73), Some(DetectedImu::Mpu9255));
    }

    #[test]
    fn env_values_override_defaults() {
        let config = ImuSensorConfig::from_env_values(
            Some("mpu9250"),
            Some("5"),
            Some("6"),
            Some("0x69"),
            Some("100000"),
        )
        .expect("env values should parse");

        assert_eq!(config.requested, RequestedImu::Mpu65x0);
        assert_eq!(config.bus.sda_pin, 5);
        assert_eq!(config.bus.scl_pin, 6);
        assert_eq!(config.bus.address, 0x69);
        assert_eq!(config.bus.baudrate_hz, 100_000);
    }
}
