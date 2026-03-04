#include "ble.h"

#include <errno.h>
#include <string.h>

#include <zephyr/bluetooth/att.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/bluetooth/hci.h>
#include <zephyr/bluetooth/uuid.h>
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/settings/settings.h>
#include <zephyr/sys/util.h>

LOG_MODULE_REGISTER(ble_node);

#define BLE_NODE_SERVICE_UUID_VAL \
  BT_UUID_128_ENCODE(0x4f6465a0, 0x1be4, 0x4a53, 0xa69b, 0x7f0d74de1000)
#define BLE_NODE_TX_UUID_VAL \
  BT_UUID_128_ENCODE(0x4f6465a0, 0x1be4, 0x4a53, 0xa69b, 0x7f0d74de1001)
#define BLE_NODE_RX_UUID_VAL \
  BT_UUID_128_ENCODE(0x4f6465a0, 0x1be4, 0x4a53, 0xa69b, 0x7f0d74de1002)

static struct bt_uuid_128 ble_node_service_uuid =
    BT_UUID_INIT_128(BLE_NODE_SERVICE_UUID_VAL);
static struct bt_uuid_128 ble_node_tx_uuid =
    BT_UUID_INIT_128(BLE_NODE_TX_UUID_VAL);
static struct bt_uuid_128 ble_node_rx_uuid =
    BT_UUID_INIT_128(BLE_NODE_RX_UUID_VAL);

static const struct bt_data ble_adv_data[] = {
    BT_DATA_BYTES(BT_DATA_FLAGS, (BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR)),
    BT_DATA_BYTES(BT_DATA_UUID128_ALL, BLE_NODE_SERVICE_UUID_VAL),
};

static struct bt_conn *active_conn;
static ble_rx_callback_t app_rx_callback;
static bt_security_t min_security = BT_SECURITY_L2;
static bool bluetooth_ready;
static bool pairing_mode_enabled;
static bool tx_notifications_enabled;
static bool link_is_secure;
static bool pairing_bonded;

static int ble_start_advertising(void) {
  int err;
  const char *name = bt_get_name();
  struct bt_data scan_rsp[] = {
      BT_DATA(BT_DATA_NAME_COMPLETE, name, strlen(name)),
  };

  err = bt_le_adv_stop();
  if (err != 0 && err != -EALREADY) {
    LOG_WRN("bt_le_adv_stop failed: %d", err);
  }

  err = bt_le_adv_start(BT_LE_ADV_CONN_FAST_1, ble_adv_data,
                        ARRAY_SIZE(ble_adv_data), scan_rsp,
                        ARRAY_SIZE(scan_rsp));
  if (err != 0) {
    LOG_ERR("bt_le_adv_start failed: %d", err);
    return err;
  }

  LOG_INF("Advertising started (pairing mode: %s)",
          pairing_mode_enabled ? "on" : "off");
  return 0;
}

static void tx_ccc_cfg_changed(const struct bt_gatt_attr *attr, uint16_t value) {
  ARG_UNUSED(attr);
  tx_notifications_enabled = (value == BT_GATT_CCC_NOTIFY);
  LOG_INF("TX notifications %s", tx_notifications_enabled ? "enabled" : "disabled");
}

static ssize_t rx_write_handler(struct bt_conn *conn,
                                const struct bt_gatt_attr *attr, const void *buf,
                                uint16_t len, uint16_t offset, uint8_t flags) {
  ARG_UNUSED(conn);
  ARG_UNUSED(attr);
  ARG_UNUSED(flags);

  if (offset != 0U) {
    return BT_GATT_ERR(BT_ATT_ERR_INVALID_OFFSET);
  }

  if (app_rx_callback != NULL && len > 0U) {
    app_rx_callback(buf, len);
  }

  return (ssize_t)len;
}

BT_GATT_SERVICE_DEFINE(
    ble_node_svc, BT_GATT_PRIMARY_SERVICE(&ble_node_service_uuid.uuid),
    BT_GATT_CHARACTERISTIC(&ble_node_tx_uuid.uuid, BT_GATT_CHRC_NOTIFY,
                           BT_GATT_PERM_NONE, NULL, NULL, NULL),
    BT_GATT_CCC(tx_ccc_cfg_changed,
                BT_GATT_PERM_READ_ENCRYPT | BT_GATT_PERM_WRITE_ENCRYPT),
    BT_GATT_CHARACTERISTIC(&ble_node_rx_uuid.uuid,
                           BT_GATT_CHRC_WRITE | BT_GATT_CHRC_WRITE_WITHOUT_RESP |
                               BT_GATT_CHRC_AUTH,
                           BT_GATT_PERM_WRITE_ENCRYPT, NULL, rx_write_handler,
                           NULL));

static void connected_cb(struct bt_conn *conn, uint8_t err) {
  int sec_err;

  if (err != 0U) {
    LOG_WRN("Connection failed (err 0x%02x)", err);
    return;
  }

  if (active_conn != NULL) {
    LOG_WRN("Unexpected extra connection, disconnecting");
    (void)bt_conn_disconnect(conn, BT_HCI_ERR_REMOTE_USER_TERM_CONN);
    return;
  }

  active_conn = bt_conn_ref(conn);
  link_is_secure = false;
  tx_notifications_enabled = false;
  LOG_INF("Connected");

  sec_err = bt_conn_set_security(conn, min_security);
  if (sec_err != 0) {
    LOG_WRN("Security request failed: %d", sec_err);
  }
}

static void disconnected_cb(struct bt_conn *conn, uint8_t reason) {
  ARG_UNUSED(reason);

  if (active_conn == conn) {
    bt_conn_unref(active_conn);
    active_conn = NULL;
    link_is_secure = false;
    tx_notifications_enabled = false;
  }

  LOG_INF("Disconnected (reason 0x%02x)", reason);

  if (bluetooth_ready) {
    int adv_err = ble_start_advertising();
    if (adv_err != 0) {
      LOG_WRN("Could not restart advertising: %d", adv_err);
    }
  }
}

static void security_changed_cb(struct bt_conn *conn, bt_security_t level,
                                enum bt_security_err err) {
  if (active_conn != conn) {
    return;
  }

  if (err != BT_SECURITY_ERR_SUCCESS) {
    link_is_secure = false;
    LOG_WRN("Security failed: %d %s", err, bt_security_err_to_str(err));
    return;
  }

  link_is_secure = (level >= min_security);
  LOG_INF("Security changed to level %u (secure=%s)", (unsigned int)level,
          link_is_secure ? "yes" : "no");
}

BT_CONN_CB_DEFINE(ble_node_conn_callbacks) = {
    .connected = connected_cb,
    .disconnected = disconnected_cb,
#if defined(CONFIG_BT_SMP) || defined(CONFIG_BT_CLASSIC)
    .security_changed = security_changed_cb,
#endif
};

static void pairing_complete_cb(struct bt_conn *conn, bool bonded) {
  if (conn == active_conn) {
    pairing_bonded = bonded;
  }
  LOG_INF("Pairing complete (bonded=%s)", bonded ? "yes" : "no");
}

static void pairing_failed_cb(struct bt_conn *conn, enum bt_security_err reason) {
  ARG_UNUSED(conn);
  pairing_bonded = false;
  LOG_WRN("Pairing failed: %d %s", reason, bt_security_err_to_str(reason));
}

static struct bt_conn_auth_info_cb auth_info_callbacks = {
    .pairing_complete = pairing_complete_cb,
    .pairing_failed = pairing_failed_cb,
};

int ble_init(const struct ble_config *config) {
  int err;

  if (bluetooth_ready) {
    return -EALREADY;
  }

  if (config != NULL) {
    if (config->device_name != NULL) {
      err = bt_set_name(config->device_name);
      if (err != 0) {
        LOG_ERR("bt_set_name failed: %d", err);
        return err;
      }
    }

    app_rx_callback = config->rx_callback;

    if (config->min_security_level != 0U) {
      if (config->min_security_level < BLE_SECURITY_L1 ||
          config->min_security_level > BLE_SECURITY_L4) {
        return -EINVAL;
      }

      min_security = (bt_security_t)config->min_security_level;
    }
  }

  err = bt_enable(NULL);
  if (err != 0) {
    LOG_ERR("bt_enable failed: %d", err);
    return err;
  }

  bluetooth_ready = true;
  pairing_mode_enabled = false;
  pairing_bonded = false;
  bt_set_bondable(false);

  err = bt_conn_auth_info_cb_register(&auth_info_callbacks);
  if (err != 0 && err != -EEXIST) {
    LOG_WRN("bt_conn_auth_info_cb_register failed: %d", err);
  }

#if defined(CONFIG_BT_SETTINGS)
  err = settings_load();
  if (err != 0) {
    LOG_WRN("settings_load failed: %d", err);
  }
#endif

  LOG_INF("BLE initialized");
  return 0;
}

int ble_enter_pairing_mode(void) {
  if (!bluetooth_ready) {
    return -EAGAIN;
  }

  pairing_mode_enabled = true;
  bt_set_bondable(true);

  return ble_start_advertising();
}

int ble_exit_pairing_mode(void) {
  if (!bluetooth_ready) {
    return -EAGAIN;
  }

  pairing_mode_enabled = false;
  bt_set_bondable(false);

  return ble_start_advertising();
}

int ble_send_packet(const uint8_t *packet, size_t len) {
  uint16_t mtu;

  if (!bluetooth_ready) {
    return -EAGAIN;
  }

  if (packet == NULL || len == 0U) {
    return -EINVAL;
  }

  if (active_conn == NULL) {
    return -ENOTCONN;
  }

  if (!link_is_secure || !tx_notifications_enabled) {
    return -EACCES;
  }

  mtu = bt_gatt_get_mtu(active_conn);
  if (mtu <= 3U || len > (size_t)(mtu - 3U)) {
    return -EMSGSIZE;
  }

  return bt_gatt_notify(active_conn, &ble_node_svc.attrs[2], packet, len);
}

bool ble_is_connected(void) { return (active_conn != NULL); }

bool ble_is_secure(void) { return link_is_secure; }

bool ble_is_paired(void) { return pairing_bonded; }
