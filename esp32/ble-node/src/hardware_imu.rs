use anyhow::{Context, Result, anyhow, bail};
use esp_idf_svc::hal::{
    delay::{BLOCK, FreeRtos},
    gpio::AnyIOPin,
    i2c::{I2cConfig, I2cDriver},
    peripherals::Peripherals,
    units::*,
};

use crate::{DetectedImu, ImuSample, ImuSensorConfig, SensorId};

const REG_SMPLRT_DIV: u8 = 0x19;
const REG_CONFIG: u8 = 0x1A;
const REG_GYRO_CONFIG: u8 = 0x1B;
const REG_ACCEL_CONFIG: u8 = 0x1C;
const REG_ACCEL_CONFIG2: u8 = 0x1D;
const REG_ACCEL_XOUT_H: u8 = 0x3B;
const REG_PWR_MGMT_1: u8 = 0x6B;
const REG_PWR_MGMT_2: u8 = 0x6C;
const REG_WHO_AM_I: u8 = 0x75;

pub struct HardwareImuSource {
    driver: HardwareDriver,
    detected: DetectedImu,
}

impl HardwareImuSource {
    pub fn new(peripherals: Peripherals, config: ImuSensorConfig) -> Result<Self> {
        let mut driver = MpuDriver::new(peripherals, config)?;
        driver.initialize()?;

        let detected = driver.detected;
        Ok(Self {
            driver: HardwareDriver::Mpu(driver),
            detected,
        })
    }

    pub fn detected(&self) -> DetectedImu {
        self.detected
    }

    pub fn next_sample(
        &mut self,
        sensor_id: SensorId,
        seq: u16,
        timestamp_ms: u32,
    ) -> Result<ImuSample> {
        match &mut self.driver {
            HardwareDriver::Mpu(driver) => driver.next_sample(sensor_id, seq, timestamp_ms),
        }
    }
}

enum HardwareDriver {
    Mpu(MpuDriver),
}

struct MpuDriver {
    i2c: I2cDriver<'static>,
    address: u8,
    detected: DetectedImu,
}

impl MpuDriver {
    fn new(peripherals: Peripherals, config: ImuSensorConfig) -> Result<Self> {
        let i2c = peripherals.i2c0;
        let sda_pin: AnyIOPin<'static> = unsafe { AnyIOPin::steal(config.bus.sda_pin) };
        let scl_pin: AnyIOPin<'static> = unsafe { AnyIOPin::steal(config.bus.scl_pin) };
        let driver_config = I2cConfig::new().baudrate(config.bus.baudrate_hz.Hz().into());
        let mut driver = I2cDriver::new(i2c, sda_pin, scl_pin, &driver_config)
            .context("failed to initialize I2C driver")?;

        let detected = read_detected_imu(&mut driver, config.bus.address).with_context(|| {
            format!(
                "failed to detect IMU at 0x{:02X} on GPIO{} / GPIO{}",
                config.bus.address, config.bus.sda_pin, config.bus.scl_pin
            )
        })?;

        if !config.requested.matches(detected) {
            bail!(
                "requested {:?} but detected {} at 0x{:02X}",
                config.requested,
                detected.label(),
                config.bus.address
            );
        }

        Ok(Self {
            i2c: driver,
            address: config.bus.address,
            detected,
        })
    }

    fn initialize(&mut self) -> Result<()> {
        self.write_reg(REG_PWR_MGMT_1, 0x80)?;
        FreeRtos::delay_ms(100);

        self.write_reg(REG_PWR_MGMT_1, 0x01)?;
        self.write_reg(REG_PWR_MGMT_2, 0x00)?;
        self.write_reg(REG_SMPLRT_DIV, 0x04)?;
        self.write_reg(REG_CONFIG, 0x03)?;
        self.write_reg(REG_GYRO_CONFIG, 0x08)?;
        self.write_reg(REG_ACCEL_CONFIG, 0x08)?;

        if self.detected.is_mpu65x0_family() {
            self.write_reg(REG_ACCEL_CONFIG2, 0x03)?;
        }

        FreeRtos::delay_ms(10);
        Ok(())
    }

    fn next_sample(
        &mut self,
        sensor_id: SensorId,
        seq: u16,
        timestamp_ms: u32,
    ) -> Result<ImuSample> {
        let mut bytes = [0u8; 14];
        self.read_regs(REG_ACCEL_XOUT_H, &mut bytes)?;

        let accel = [
            i16::from_be_bytes([bytes[0], bytes[1]]),
            i16::from_be_bytes([bytes[2], bytes[3]]),
            i16::from_be_bytes([bytes[4], bytes[5]]),
        ];
        let gyro = [
            i16::from_be_bytes([bytes[8], bytes[9]]),
            i16::from_be_bytes([bytes[10], bytes[11]]),
            i16::from_be_bytes([bytes[12], bytes[13]]),
        ];

        Ok(ImuSample::new(sensor_id, seq, timestamp_ms, accel, gyro))
    }

    fn write_reg(&mut self, reg: u8, value: u8) -> Result<()> {
        self.i2c
            .write(self.address, &[reg, value], BLOCK)
            .map_err(|err| anyhow!("I2C write to 0x{:02X} failed: {err}", self.address))
    }

    fn read_regs(&mut self, reg: u8, buffer: &mut [u8]) -> Result<()> {
        self.i2c
            .write_read(self.address, &[reg], buffer, BLOCK)
            .map_err(|err| anyhow!("I2C read from 0x{:02X} failed: {err}", self.address))
    }
}

fn read_detected_imu(i2c: &mut I2cDriver<'static>, address: u8) -> Result<DetectedImu> {
    let mut who_am_i = [0u8; 1];
    i2c.write_read(address, &[REG_WHO_AM_I], &mut who_am_i, BLOCK)
        .map_err(|err| anyhow!("WHO_AM_I read failed: {err}"))?;

    DetectedImu::from_who_am_i(who_am_i[0]).ok_or_else(|| {
        anyhow!(
            "unsupported IMU with WHO_AM_I=0x{:02X} at 0x{:02X}",
            who_am_i[0],
            address
        )
    })
}
