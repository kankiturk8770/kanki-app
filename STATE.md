# Kanki VPN app — state

## Panel 3.69 codes (branch `ccr-94a1e97e-wsl617`)
- `PanelLink.BARE_ONLY`: 10–40 digits (panel 3.69 makes 39-digit codes, > 128 bits; older accounts keep 19). The full
  `kanki-<id>-<code>` form already took up to 64. Test: `SubLinkTest.every_input_shape_is_read`.
- Not run here: no Android SDK in this container (Go cores: vet + `-race` tests pass).

## 1.45.1 (branch `ccr-94a1e97e-wsl617`, with panel 3.67.2)

Root cause of «the app fetches nothing» (1.45.0)
- `PanelTls.Pinned` was a plain `X509TrustManager` and called the system's two-argument `checkServerTrusted`. On a
  phone the system trust manager is the network-security-config one, which throws for that call whenever the config
  has a `<domain-config>` ("Domain specific configurations require that hostname aware checkServerTrusted…"); ours has
  one (cleartext probes). So every panel TLS handshake failed, on every host, before a byte was sent. Robolectric has
  no network-security-config, so the tests passed. Fixed: `X509ExtendedTrustManager` delegating to the hostname-aware
  check, and with no pins the platform socket factory is used untouched. Regression test: `SubLinkTest`.
- Also on the path: the page was never asked with the header (`fetch(url, null)` took the legacy `/sub/<code>`), a
  `/sub/h/<hash>` link or a bare code was refused at the login box, and hosts with a path (`/admin`, `/sub/`) or
  separated by new lines were dropped silently by the build.

Done
- `PanelLink.kt`: host normalization (`https://host[:port]`, path and trailing `/` dropped, `http://`/no scheme →
  https, user part refused), every input shape (`kanki-<id>-<code>`, bare digits, `/sub/<code>`, `/sub/h/<hash>`,
  `/sub/me?sub=`, `/api/config/…?sub=`, relay `:2053` links, `/sub/sub/`, RTL marks, Persian digits), `PanelError`
  with specific Persian messages (no hosts in build, DNS, network, TLS, pin, 401/404, relay 405/502, seal, parse).
- `SubscriptionApi`: `/sub/me` + `X-Kanki-Sub` + `X-Kanki-Device` + `X-Kanki-Seal: kseal1` on every host; 400/401/403/
  404/405 → `/sub/<code>` on the same host; then the next host (race, best reason kept). Bare code / hash: plain
  (unsealed) page, code taken from the page, then the normal sealed login. Same-origin HTTPS redirects only (max 3),
  8 MB body cap, sealed and plain answers both read; 200 pages that are not subscription pages are refused.
- `SecurePrefs`: AEADBadTagException / KeyStoreException / ProviderException / damaged blob → only the key blob, the
  Keystore entry and the sealed values go, a new key is made (login works again).
- Gradle: same normalization at build time, fails when `KANKI_PANEL_HOSTS` is set but yields no host, logs the count.
  Windows CI: the secret is split/joined into one `-X` value, fails the same way.
- Windows `internal/sub`: `link.go` (same rules), `/sub/me` + fallback, `Resolve` for bare/hash, same-origin redirects.
- UI: DNS bar under the Connect button (48 dp, active/selected/off), Smart mode stays selected (only a user pick
  changes the mode; the Smart tile stays usable while connecting), `limit_gb == 0` → `∞ / نامحدود`, theme cards drawn
  only with their own palette (text sample, mode dots, double border, radio semantics), server cells 48 dp with
  tunnel/direct badges, probes refuse to measure through the tunnel (no 4–5 s spikes), gaming list hides gms/gsf
  (always open) and Play Store/Chrome (always closed; `GameOnly` no longer adds Play Store), modes a plan lacks are
  dimmed and never open an empty sheet; the filter sheet opens on a tab that exists.

Secrets the CI needs
- `KANKI_PANEL_HOSTS` (Android + Windows builds): `https://host[:port]` entries, separated by commas, spaces or lines;
  a path after the host is dropped.
- `KANKI_PANEL_PINS` (optional): base64 SHA-256 SPKI pins, include the backup key.

## 1.45.0

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
