#ifndef NODE_BLE_H_
#define NODE_BLE_H_
/// Library for all bluetooth operations related to device posture nodes

int init_ble(/* some kind of config*/);

int send_packet(/* some kind of packet def, likely sourced from #include "packet.h" */);

#endif