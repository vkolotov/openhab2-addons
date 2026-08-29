# Bluetooth Binding: Direct-BT Adapter

This extension supports Bluetooth access via a [Direct-BT](https://jausoft.com/projects/direct_bt/build/documentation/cpp/html/index.html) controlled adapter.

Unlike the BlueZ transport, Direct-BT is a user-space Bluetooth stack: it talks to the HCI controller directly and does **not** use the `bluetoothd` daemon.
This gives the binding full control of the connection lifecycle (connection parameters, security, recovery), which makes it a good fit for battery-powered LE peripherals on marginal links, at the cost of requiring exclusive ownership of the adapter.

The binding drives the radio with a level-triggered reconciler: it periodically polls the native adapter/device state and issues idempotent corrective commands, instead of trusting individual command results or connection events (which controllers can drop).
A silently dropped connection is therefore detected and repaired automatically, wedged controllers are recovered with rate-limited resets, and discovery is switched on and off as devices need to be (re)found.

## Requirements

- Linux with a Bluetooth LE capable adapter (kernel HCI interface). Only platforms with Direct-BT native library support (e.g. Linux x86_64 and arm) can run this transport.
- **Exclusive adapter ownership**: `bluetoothd` must not manage the adapter used by this bridge. Either disable BlueZ for that adapter or stop/mask the `bluetooth` service for it.
- **Capabilities**: the openHAB JVM process needs `CAP_NET_ADMIN` and `CAP_NET_RAW` to open the HCI user channel. For a systemd installation, add a drop-in:

  ```ini
  # /etc/systemd/system/openhab.service.d/directbt.conf
  [Service]
  AmbientCapabilities=CAP_NET_ADMIN CAP_NET_RAW
  ```

  Membership of the `bluetooth` group alone is **not** sufficient.

## Supported Things

| Thing Type ID | Description                                                    |
|---------------|----------------------------------------------------------------|
| directbt      | A Bluetooth adapter controlled via the Direct-BT user-space stack (Bridge) |

## Discovery

Adapters usable by Direct-BT are discovered automatically and appear in the inbox.
Note that the same physical adapter may also be offered by the BlueZ transport — only one transport can own an adapter at a time.

Once an adapter bridge is online, Bluetooth devices in range are discovered like with any other Bluetooth bridge (subject to the background discovery setting below).

## Bridge Configuration

| Parameter                       | Required | Default | Description                                                              |
|---------------------------------|----------|---------|--------------------------------------------------------------------------|
| address                         | yes      |         | The Bluetooth address of the adapter (XX:XX:XX:XX:XX:XX)                 |
| backgroundDiscovery             | no       | false   | Continuously scan for new devices to surface them in the inbox           |
| inactiveDeviceCleanupInterval   | no       | 60      | How often (seconds) inactive device cleanup runs                         |
| inactiveDeviceCleanupThreshold  | no       | 300     | How long (seconds) a device may be radio-silent before cleanup           |

## Connection Security

Devices connected through this transport honour the `connectionSecurity` configuration of the generic Bluetooth device Thing:

- `none` (default): unencrypted connection.
- `encrypted`: encrypted link via Just-Works LE pairing. Strict — the binding never falls back to an unencrypted connection.
- `pin`: encrypted and MITM-authenticated link via Passkey Entry, using the `passkey` configured on the device Thing. Strict — the connection is refused (fail closed) rather than downgraded if the peer cannot pair authenticated.

Pairing keys (bonds) are persisted under `${OPENHAB_USERDATA}/bluetooth/directbt-keys/<adapter>` (owner-only permissions) so paired devices survive a restart without re-pairing.
A bond the peer no longer honours is detected and cleared automatically, triggering a fresh pairing.

## Example

demo.things:

```java
Bridge bluetooth:directbt:hci0 [ address="12:34:56:78:9A:BC", backgroundDiscovery=false ]
```
