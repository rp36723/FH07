use crate::{
    imu::ImuSource,
    protocol::{ImuSample, SensorId},
};

#[derive(Debug, Clone)]
pub struct MockImuSource {
    tick: u32,
    seed: u32,
}

impl MockImuSource {
    pub fn new(sensor_id: SensorId) -> Self {
        Self {
            tick: 0,
            seed: sensor_id as u32 + 1,
        }
    }

    fn triangle(&self, period: u32, amplitude: i16, phase_offset: u32) -> i16 {
        let period = period.max(4);
        let position = (self.tick + phase_offset + self.seed * 7) % period;
        let half = period / 2;
        let amp = amplitude as i32;
        let scaled = if position < half {
            -amp + ((position as i32 * 2 * amp) / half as i32)
        } else {
            amp - (((position - half) as i32 * 2 * amp) / half as i32)
        };

        scaled.clamp(i16::MIN as i32, i16::MAX as i32) as i16
    }
}

impl ImuSource for MockImuSource {
    fn next_sample(&mut self, sensor_id: SensorId, seq: u16, timestamp_ms: u32) -> ImuSample {
        let accel = [
            self.triangle(64, 16_000, 0),
            self.triangle(48, 12_000, 11),
            self.triangle(80, 8_000, 23),
        ];
        let gyro = [
            self.triangle(40, 2_000, 3),
            self.triangle(56, 1_500, 13),
            self.triangle(72, 1_000, 29),
        ];

        self.tick = self.tick.wrapping_add(1);
        ImuSample::new(sensor_id, seq, timestamp_ms, accel, gyro)
    }
}

#[cfg(test)]
mod tests {
    use super::{ImuSource, MockImuSource};

    #[test]
    fn samples_change_over_time() {
        let mut imu = MockImuSource::new(3);
        let first = imu.next_sample(3, 0, 0);
        let second = imu.next_sample(3, 1, 20);
        assert_ne!(first.ax, second.ax);
        assert_ne!(first.gy, second.gy);
    }

    #[test]
    fn different_sensor_ids_produce_distinct_waveforms() {
        let mut a = MockImuSource::new(1);
        let mut b = MockImuSource::new(2);
        let a_sample = a.next_sample(1, 0, 0);
        let b_sample = b.next_sample(2, 0, 0);
        assert_ne!(a_sample.ax, b_sample.ax);
        assert_ne!(a_sample.gz, b_sample.gz);
    }
}
