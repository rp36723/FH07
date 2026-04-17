pub const PROTOCOL_VERSION: u8 = 1;
pub const SAMPLE_PAYLOAD_LEN: usize = 20;
pub const COMPANY_IDENTIFIER: u16 = 0xFFFF;
pub const ADV_FLAGS: u8 = 0x06;
pub const GATT_SERVICE_UUID: &str = "12345678-1234-5678-1234-56789abc0000";
pub const NOTIFY_CHARACTERISTIC_UUID: &str = "12345678-1234-5678-1234-56789abc0001";
pub const STATUS_CHARACTERISTIC_UUID: &str = "12345678-1234-5678-1234-56789abc0002";

pub type SensorId = u8;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ImuSample {
    pub version: u8,
    pub sensor_id: SensorId,
    pub seq: u16,
    pub timestamp_ms: u32,
    pub ax: i16,
    pub ay: i16,
    pub az: i16,
    pub gx: i16,
    pub gy: i16,
    pub gz: i16,
}

impl ImuSample {
    pub fn new(
        sensor_id: SensorId,
        seq: u16,
        timestamp_ms: u32,
        accel: [i16; 3],
        gyro: [i16; 3],
    ) -> Self {
        Self {
            version: PROTOCOL_VERSION,
            sensor_id,
            seq,
            timestamp_ms,
            ax: accel[0],
            ay: accel[1],
            az: accel[2],
            gx: gyro[0],
            gy: gyro[1],
            gz: gyro[2],
        }
    }

    pub fn encode(self) -> [u8; SAMPLE_PAYLOAD_LEN] {
        let mut bytes = [0u8; SAMPLE_PAYLOAD_LEN];
        bytes[0] = self.version;
        bytes[1] = self.sensor_id;
        bytes[2..4].copy_from_slice(&self.seq.to_le_bytes());
        bytes[4..8].copy_from_slice(&self.timestamp_ms.to_le_bytes());
        bytes[8..10].copy_from_slice(&self.ax.to_le_bytes());
        bytes[10..12].copy_from_slice(&self.ay.to_le_bytes());
        bytes[12..14].copy_from_slice(&self.az.to_le_bytes());
        bytes[14..16].copy_from_slice(&self.gx.to_le_bytes());
        bytes[16..18].copy_from_slice(&self.gy.to_le_bytes());
        bytes[18..20].copy_from_slice(&self.gz.to_le_bytes());
        bytes
    }

    pub fn decode(payload: &[u8]) -> Option<Self> {
        if payload.len() != SAMPLE_PAYLOAD_LEN {
            return None;
        }

        Some(Self {
            version: payload[0],
            sensor_id: payload[1],
            seq: u16::from_le_bytes(payload[2..4].try_into().ok()?),
            timestamp_ms: u32::from_le_bytes(payload[4..8].try_into().ok()?),
            ax: i16::from_le_bytes(payload[8..10].try_into().ok()?),
            ay: i16::from_le_bytes(payload[10..12].try_into().ok()?),
            az: i16::from_le_bytes(payload[12..14].try_into().ok()?),
            gx: i16::from_le_bytes(payload[14..16].try_into().ok()?),
            gy: i16::from_le_bytes(payload[16..18].try_into().ok()?),
            gz: i16::from_le_bytes(payload[18..20].try_into().ok()?),
        })
    }
}

pub fn encode_manufacturer_data(sample: ImuSample) -> [u8; 2 + SAMPLE_PAYLOAD_LEN] {
    let mut bytes = [0u8; 2 + SAMPLE_PAYLOAD_LEN];
    bytes[..2].copy_from_slice(&COMPANY_IDENTIFIER.to_le_bytes());
    bytes[2..].copy_from_slice(&sample.encode());
    bytes
}

pub fn decode_manufacturer_data(company_identifier: u16, payload: &[u8]) -> Option<ImuSample> {
    if company_identifier != COMPANY_IDENTIFIER {
        return None;
    }

    let sample = ImuSample::decode(payload)?;
    (sample.version == PROTOCOL_VERSION).then_some(sample)
}

pub fn advertisement_bytes(sample: ImuSample) -> [u8; 27] {
    let manufacturer = encode_manufacturer_data(sample);
    let mut bytes = [0u8; 27];
    bytes[0] = 2;
    bytes[1] = 0x01;
    bytes[2] = ADV_FLAGS;
    bytes[3] = 23;
    bytes[4] = 0xFF;
    bytes[5..].copy_from_slice(&manufacturer);
    bytes
}

pub fn sensor_id_from_mac_be(mac_be: [u8; 6]) -> SensorId {
    mac_be[5]
}

#[cfg(test)]
mod tests {
    use super::{
        ADV_FLAGS, COMPANY_IDENTIFIER, ImuSample, PROTOCOL_VERSION, advertisement_bytes,
        decode_manufacturer_data,
    };

    fn sample() -> ImuSample {
        ImuSample::new(7, 42, 1_337, [1, -2, 3], [-4, 5, -6])
    }

    #[test]
    fn sample_roundtrip_works() {
        let encoded = sample().encode();
        let decoded = ImuSample::decode(&encoded).expect("payload should decode");
        assert_eq!(decoded, sample());
    }

    #[test]
    fn advertisement_layout_matches_plan() {
        let bytes = advertisement_bytes(sample());
        assert_eq!(bytes[0..3], [2, 0x01, ADV_FLAGS]);
        assert_eq!(bytes[3], 23);
        assert_eq!(bytes[4], 0xFF);
        assert_eq!(u16::from_le_bytes([bytes[5], bytes[6]]), COMPANY_IDENTIFIER);
        assert_eq!(bytes.len(), 27);
    }

    #[test]
    fn manufacturer_decode_rejects_wrong_version() {
        let mut encoded = sample().encode();
        encoded[0] = PROTOCOL_VERSION.wrapping_add(1);
        assert!(decode_manufacturer_data(COMPANY_IDENTIFIER, &encoded).is_none());
    }
}
