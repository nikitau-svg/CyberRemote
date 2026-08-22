# MRP Now Playing foundation

This document describes the isolated MRP layer in `:protocol`. Companion Link
remains the command transport used by the Android app and is not coupled to
these classes.

## Implemented and testable without a TV

- Generated proto2 MediaRemote messages with a complete extension registry
  for state, client/player updates, commands, artwork and pairing.
- Legacy/direct MRP varint framing, whole-message ChaCha20-Poly1305, HAP
  pair-setup and pair-verify, and independent MRP credentials.
- Push reducer for active client/player, queue metadata, playback state,
  position anchor/rate/timestamps and artwork updates.
- Explicit `play()` and `pause()` commands with response/error correlation.
- PlaybackQueue artwork request and embedded artwork extraction.
- HAP-IP 1024-byte channel framing and AirPlay 2 DataStream framing/bplist
  envelope codecs.
- mDNS capability planning that never selects a known-inoperative direct MRP
  service on tvOS 15 or newer.

## Runtime gap on modern Apple TV

Modern tvOS does not accept the standalone `_mediaremotetv._tcp` path. Starting
with tvOS 15, MRP is transported through `_airplay._tcp` in an AirPlay 2 remote
control DataStream. The remaining adapter must perform this live flow:

1. AirPlay HAP pair-setup over `/pair-pin-start` and `/pair-setup`; store the
   AirPlay credential separately from Companion and direct-MRP credentials.
2. AirPlay pair-verify over `/pair-verify`; enable HAP encryption on the control
   connection.
3. Encrypted RTSP `SETUP` to obtain and connect the event channel, then
   `RECORD`.
4. A second `SETUP` for stream type 130; connect the returned data port using
   DataStream keys derived from its random seed.
5. Wrap that data socket in `MrpWireTransport`, acknowledge incoming `sync`
   frames, and send `/feedback` every two seconds while the tunnel is active.

`MrpCapabilityProbe` therefore returns `FOUNDATION_ONLY`, never `READY`, for a
modern AirPlay 2 target with credentials. This is deliberate: pairing UI,
dynamic ports, event replies, command acknowledgements and artwork URL behavior
must be verified on the actual Apple TV before the app treats the tunnel as a
working data source.

## Source

The wire behavior and protobuf field definitions are derived from pyatv 0.18.0
at commit `b277a4c8222ecdcbaab8a24e3e713ca44765adb4`. See
`THIRD_PARTY_NOTICES.md`.
