# Cyclone 3.5 virtual-device provider

Virtualization is a lifecycle service orthogonal to `phone.*`. Once an instance boots and its
authorized ADB endpoint appears in the fleet, it is an ordinary Cyclone device.

## Lifecycle API

The internal `VirtualDeviceProvider` seam exposes health, image discovery, create, start, stop,
reset, delete and configure. The service also exposes instance status, endpoint metadata and a
persistent registry. Clone and snapshot/restore are intentionally absent until a provider proves
those operations reliably.

Instances persist provider ID, Cyclone device ID, display/storage/network configuration, data path,
loopback console allocation, lifecycle state and safe error text. Registry reconstruction never
trusts a persisted `RUNNING` or `STARTING` claim.

## Android Emulator provider

The launch candidate is an allow-listed Android Emulator/AVD adapter. It uses fixed executable
vectors for `avdmanager`, `emulator` and `adb`, bounded startup polling, explicit display config,
and `-no-window`/`-no-audio`/`swiftshader_indirect` defaults suitable for a local desktop service.
Ports are leased as console/ADB pairs and released after deletion or failed creation.

At this host checkpoint the provider is **UNAVAILABLE / UNVERIFIED**: the launch PC has no Android
SDK emulator, AVD image or Docker daemon, and WSL2 has no usable binder device for ReDroid. The API
reports this state and returns HTTP 503 for create rather than claiming a virtual phone exists.
ReDroid remains a documented future Linux-provider option; it must be tested on a compatible kernel
before being advertised.

## Networking and policy

All provider endpoints bind to `127.0.0.1`; network mode currently accepts only `loopback`. No
public unauthenticated ADB, identity spoofing, anti-abuse evasion, arbitrary host command, or model
shell access is provided.
