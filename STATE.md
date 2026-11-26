# Kanki VPN app — state

## 1.45.0 (branch `ccr-b78076f4-potfo2`, with panel 3.67.1)

Done
- kmix-core: protocol 3 Hello/HelloAck (gaming flag, rotating 8-byte hint, 10-min cap, dropped when the server goes
  silent), hint on non-KCP packets within MaxWire, single endpoint, second network silent until loss >0.4 % / jitter
  spike / silence, make-before-break port hop at 400 ms stall, proportional RLC (server 3.67 policy), faster loss
  reports in a game's first 3 s. Tests: obfs hint wire format, hop MBB + cancel, dual on/off, hello replay/foreign
  session, old-server backoff; integration suite against the real 3.67 server binary (`SRVBIN`, `HOSTIP`).
- Android: no panel domain in source (BuildConfig from `KANKI_PANEL_HOSTS`; optional SPKI pins `KANKI_PANEL_PINS`),
  code only in `X-Kanki-Sub` (`/sub/me`, config downloads; fallback to the old URL on older panels), HTTPS-only panel
  client with system CAs and no redirects, Keystore-wrapped encrypted preferences (names and values), 4 h offline-first
  cache, Xray fragment, OpenVPN and emergency-chat remnants removed, R8 resource shrinking, engine Mutex, strict
  gaming gate (games + gms/gsf + system DNS; Play Store/Chrome blocked with RST/ICMP), metered KANKI MIX tun,
  automatic game detection (prefix families), protected and staggered probes, UI polish (Part 4).
- Windows: panel hosts from `-ldflags -X kankivpn/internal/sub.PanelHosts=…` (CI secret), no domain in source.
- Panel 3.67.1 (kanki-panel, same branch name): `/api/config` reads the code from `X-Kanki-Sub`.

Secrets the CI needs
- `KANKI_PANEL_HOSTS` (Android + Windows builds): comma separated `https://host[:port]` origins.
- `KANKI_PANEL_PINS` (optional): base64 SHA-256 SPKI pins, include the backup key.

Notes
- SPKI pins are not in the source: Let's Encrypt keys rotate; without the secret the client uses system CAs + hostname.
- EncryptedSharedPreferences was not used (it adds Tink to the APK); `SecurePrefs` wraps a data key with the Keystore.
