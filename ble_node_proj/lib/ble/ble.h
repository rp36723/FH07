#ifndef NODE_BLE_H_
#define NODE_BLE_H_
/* Library for BLE operations used by posture nodes. */

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef void (*ble_rx_callback_t)(const uint8_t *data, size_t len);

enum ble_security_level {
  BLE_SECURITY_L1 = 1U, /* No encryption */
  BLE_SECURITY_L2 = 2U, /* Encrypted link */
  BLE_SECURITY_L3 = 3U, /* Encrypted + MITM */
  BLE_SECURITY_L4 = 4U, /* LE Secure Connections + MITM */
};

struct ble_config {
  const char *device_name;
  uint8_t min_security_level;
  ble_rx_callback_t rx_callback;
};

/**
 * @brief Initialize the BLE stack and node GATT service.
 *
 * The service exposes:
 * - a notify-only TX characteristic for outbound packet streaming, and
 * - a secure RX characteristic for inbound control/data writes.
 *
 * @param config Optional initialization configuration. Pass NULL for defaults.
 *               Defaults are current device name, no RX callback, and
 *               security level BLE_SECURITY_L2.
 *
 * @retval 0 Success.
 * @retval -EALREADY BLE has already been initialized.
 * @retval -EINVAL Invalid configuration value.
 * @retval <0 Other stack error from Zephyr Bluetooth APIs.
 */
int ble_init(const struct ble_config *config);

/**
 * @brief Enter pairing mode and start connectable advertising.
 *
 * Pairing mode enables bondable behavior for new peers and advertises
 * the custom BLE node service UUID.
 *
 * @retval 0 Success.
 * @retval -EAGAIN BLE is not initialized yet.
 * @retval <0 Advertising start error.
 */
int ble_enter_pairing_mode(void);

/**
 * @brief Exit pairing mode and keep advertising for normal reconnects.
 *
 * Exiting pairing mode disables bondable behavior so new bonds are not created.
 *
 * @retval 0 Success.
 * @retval -EAGAIN BLE is not initialized yet.
 * @retval <0 Advertising restart error.
 */
int ble_exit_pairing_mode(void);

/**
 * @brief Send a raw packet to the connected peer using notifications.
 *
 * Packet format is application-defined; this API sends raw bytes unchanged.
 *
 * @param packet Pointer to packet bytes.
 * @param len Packet length in bytes.
 *
 * @retval 0 Success.
 * @retval -EAGAIN BLE is not initialized yet.
 * @retval -EINVAL Invalid arguments.
 * @retval -ENOTCONN No active connection.
 * @retval -EACCES Link is not secure or notifications are not enabled.
 * @retval -EMSGSIZE Packet exceeds current ATT payload size.
 * @retval <0 Other notify error.
 */
int ble_send_packet(const uint8_t *packet, size_t len);

/**
 * @brief Check whether a BLE peer is currently connected.
 *
 * @return true if connected, false otherwise.
 */
bool ble_is_connected(void);

/**
 * @brief Check whether the active BLE link meets configured security level.
 *
 * @return true if secure at or above configured minimum security level.
 */
bool ble_is_secure(void);

/**
 * @brief Check whether pairing completed with bonding on current session.
 *
 * @return true if the current peer bonded during pairing.
 */
bool ble_is_paired(void);

#ifdef __cplusplus
}
#endif

#endif
