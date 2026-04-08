use std::vec::Vec;

use crate::protocol::{ImuSample, PROTOCOL_VERSION, SensorId};

pub const MAX_SENSORS: usize = 8;
pub const STALE_TIMEOUT_MS: u32 = 3_000;
pub const NETWORK_STATUS_HEADER_LEN: usize = 6;
pub const NETWORK_STATUS_ENTRY_LEN: usize = 5;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct SensorRecord {
    sensor_id: SensorId,
    last_sample: ImuSample,
    last_seen_ms: u32,
}

#[derive(Debug, Default)]
pub struct SensorTable {
    entries: [Option<SensorRecord>; MAX_SENSORS],
}

impl SensorTable {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn ingest(&mut self, sample: ImuSample, seen_ms: u32) {
        if let Some(existing) = self
            .entries
            .iter_mut()
            .flatten()
            .find(|entry| entry.sensor_id == sample.sensor_id)
        {
            existing.last_sample = sample;
            existing.last_seen_ms = seen_ms;
            return;
        }

        if let Some(slot) = self.entries.iter_mut().find(|entry| entry.is_none()) {
            *slot = Some(SensorRecord {
                sensor_id: sample.sensor_id,
                last_sample: sample,
                last_seen_ms: seen_ms,
            });
            return;
        }

        let replace_index = self
            .entries
            .iter()
            .enumerate()
            .min_by_key(|(_, entry)| entry.map(|record| record.last_seen_ms).unwrap_or(u32::MAX))
            .map(|(index, _)| index)
            .expect("table is never empty here");

        self.entries[replace_index] = Some(SensorRecord {
            sensor_id: sample.sensor_id,
            last_sample: sample,
            last_seen_ms: seen_ms,
        });
    }

    pub fn prune_stale(&mut self, now_ms: u32, stale_after_ms: u32) -> usize {
        let mut removed = 0;
        for entry in &mut self.entries {
            let should_remove = entry
                .as_ref()
                .is_some_and(|record| now_ms.wrapping_sub(record.last_seen_ms) > stale_after_ms);

            if should_remove {
                *entry = None;
                removed += 1;
            }
        }

        removed
    }

    pub fn active_count(&self, now_ms: u32, stale_after_ms: u32) -> usize {
        self.active_records(now_ms, stale_after_ms).len()
    }

    pub fn encode_network_status(
        &self,
        uptime_ms: u32,
        now_ms: u32,
        stale_after_ms: u32,
    ) -> Vec<u8> {
        let active = self.active_records(now_ms, stale_after_ms);
        let mut bytes =
            Vec::with_capacity(NETWORK_STATUS_HEADER_LEN + active.len() * NETWORK_STATUS_ENTRY_LEN);

        bytes.push(PROTOCOL_VERSION);
        bytes.extend_from_slice(&uptime_ms.to_le_bytes());
        bytes.push(active.len() as u8);

        for record in active {
            let age_ms = now_ms
                .wrapping_sub(record.last_seen_ms)
                .min(u16::MAX as u32) as u16;
            bytes.push(record.sensor_id);
            bytes.extend_from_slice(&record.last_sample.seq.to_le_bytes());
            bytes.extend_from_slice(&age_ms.to_le_bytes());
        }

        bytes
    }

    fn active_records(&self, now_ms: u32, stale_after_ms: u32) -> Vec<SensorRecord> {
        let mut active: Vec<SensorRecord> = self
            .entries
            .iter()
            .flatten()
            .copied()
            .filter(|record| now_ms.wrapping_sub(record.last_seen_ms) <= stale_after_ms)
            .collect();

        active.sort_by_key(|record| record.sensor_id);
        active
    }
}

#[cfg(test)]
mod tests {
    use crate::protocol::ImuSample;

    use super::{
        NETWORK_STATUS_ENTRY_LEN, NETWORK_STATUS_HEADER_LEN, STALE_TIMEOUT_MS, SensorTable,
    };

    fn sample(sensor_id: u8, seq: u16) -> ImuSample {
        ImuSample::new(sensor_id, seq, seq as u32 * 20, [1, 2, 3], [4, 5, 6])
    }

    #[test]
    fn table_tracks_latest_sample_per_sensor() {
        let mut table = SensorTable::new();
        table.ingest(sample(1, 1), 100);
        table.ingest(sample(1, 2), 120);

        let status = table.encode_network_status(120, 120, STALE_TIMEOUT_MS);
        assert_eq!(status[5], 1);
        assert_eq!(u16::from_le_bytes([status[7], status[8]]), 2);
    }

    #[test]
    fn stale_entries_are_removed() {
        let mut table = SensorTable::new();
        table.ingest(sample(1, 1), 0);
        table.ingest(sample(2, 1), 100);

        let removed = table.prune_stale(3_101, STALE_TIMEOUT_MS);
        assert_eq!(removed, 2);
        assert_eq!(table.active_count(3_101, STALE_TIMEOUT_MS), 0);
    }

    #[test]
    fn network_status_is_compact_and_sorted() {
        let mut table = SensorTable::new();
        table.ingest(sample(3, 7), 10);
        table.ingest(sample(1, 4), 20);

        let status = table.encode_network_status(500, 520, STALE_TIMEOUT_MS);
        assert_eq!(
            status.len(),
            NETWORK_STATUS_HEADER_LEN + 2 * NETWORK_STATUS_ENTRY_LEN
        );
        assert_eq!(status[0], 1);
        assert_eq!(status[5], 2);
        assert_eq!(status[6], 1);
        assert_eq!(status[11], 3);
    }
}
