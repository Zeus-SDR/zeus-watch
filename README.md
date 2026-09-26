# Zeus Watch

A native Wear OS client for a Zeus station on the local network. It draws its
own interface, plays receive audio through the watch, and sends microphone audio
back — no WebView and no browser in the path. The hosted web remote stays as the
fallback for the one case a LAN app cannot cover: the station is not on this
network.

The app requires Wear OS with Android API 26 or later and a reachable HTTPS Zeus
station. It declares watch hardware and standalone operation and requests
`INTERNET`, `VIBRATE`, and `RECORD_AUDIO`. The microphone is requested only when
the operator first presses PTT, and a granted permission never keys the radio on
its own.

## Interface

Pages are swiped horizontally, each sized to the square that fits inside the
round face so nothing is clipped by the bezel. The dots along the bottom show
the current page.

| Page | What it does |
| --- | --- |
| PTT | Press-and-hold transmit with the current station status |
| Radio | Frequency, S-meter, and MOX; forward power and SWR while transmitting |
| Scope | Mini panadapter over a scrolling waterfall, with step tuning |
| Frequency | Direct kHz entry |
| Band, Mode, Filter | Three-row selectors |
| Switches | Speaker, scope, TUNE, two-tone, preamp, and keep-screen-on |
| Levels | One control at a time: AF, AGC-T, ATT, mic gain, drive |

Tuning is on the outer ring: drag around the edge, or use the rotating bezel or
crown, and the radio moves by the station's configured step with a tick of
haptic feedback per detent. The dial moves locally on every detent while only
one retune is in flight, so turning stays responsive on a slow link and a stale
station echo cannot drag the frequency backwards.

Colours come from the bundled Zeus design-token snapshot, copied into the APK at
build time and parsed at startup. The amber trace on the scope is the station's
signal-strength colour and is used for signal only, never for chrome.

## Transmit safety

Transmit never touches the station's global REST endpoints. The app opens a
`/product/watch/session` socket that holds a private transmit lease and sends a
heartbeat twice a second; two seconds of silence revokes the lease and drives
the station to a safe idle. Losing the microphone drops the lease too — the
heartbeat stops the instant capture fails, so a dead audio path can never hold
the radio keyed. Leaving the app releases PTT, and returning to it restores
receive only.

## Third-party libraries

OkHttp, Okio, and the Kotlin standard library, pinned by version and SHA-256 in
`dependencies.json`. The build downloads them to a cache outside the repository,
verifies each hash, and fails rather than shipping a jar it cannot account for.
See `THIRD-PARTY-NOTICES.txt`.

## Build

Install PowerShell 7, a JDK with `java`, `javac`, and `keytool` on PATH, and
Android SDK platform `android-36` plus build tools `36.0.0`. Set
`ANDROID_SDK_ROOT`, or pass `-SdkRoot`. Windows also checks
`%LOCALAPPDATA%/Android/Sdk`.

```powershell
pwsh ./build.ps1 -StationUrl 'https://app.zeussdr.com/?remote=YOUR_CALLSIGN' -LanUrl 'https://station.example:6443/' -LanCertificate '/path/station-public.cer' -OutputDirectory '/path/outside/repository/zeus-watch'
```

`-LanUrl` and `-LanCertificate` go together and configure the native station;
`-StationUrl` is the hosted fallback. Use the station's public X.509 certificate
in DER or PEM format, never a private key or a password-protected identity
bundle. The build stores only the public certificate and the configured
addresses, in generated assets outside the repository. The watch trusts the
certificate's public key, not its exact bytes: the station re-issues its
certificate when its LAN addresses change but keeps the key, so a rebuild is only
needed if the station's key itself is replaced. If the station answers with a
different key or a certificate that does not cover the LAN address, the watch
says so instead of opening the hosted remote. Do not use an address
containing a secret: the configured address is readable from the APK.

`-DependencyCache` points at the jar cache; it defaults to a `dependencies`
folder under the output directory and must also sit outside the repository.

The script normalizes each URL to `watch=1`, removes desktop/mobile overrides,
strips `remote` from the LAN address, and rejects HTTP or embedded credentials.
Build outputs are `zeus-watch.apk`, a reusable local debug keystore, and
isolated build intermediates. Keep the output directory when rebuilding so
updates use the same signing key. The key uses the standard development password
and is suitable for local sideloading, not distribution signing. Neither APK nor
key belongs in source control.

The pipeline uses [AAPT2](https://developer.android.com/tools/aapt2), Java
compilation, D8, zipalign, and apksigner directly. It verifies the signature and
ZIP alignment before reporting the APK path and SHA-256. No Gradle project is
required.

## Finding the station

At startup the app checks `/product/status` over HTTPS on a background worker.
It requires HTTP 200, the exact configured certificate, current certificate
validity, and the platform's hostname validation, and it follows no redirects.
Socket connect and read timeouts are two seconds each. An unavailable station, a
changed or expired certificate, or a hostname mismatch opens the hosted remote
in the watch browser instead. Rebuild with the new public certificate after a
station certificate rotation.

The same exact-certificate rule covers every later connection: control requests,
the realtime socket, and the transmit-lease socket all pin the same bytes. No
certificate authority is consulted, and no trust is granted to the browser.

## Tests

The Android-free parts — certificate pinning, the station wire format, the
palette parser, and the tuning coalescer — run on a plain JVM with no device or
emulator:

```powershell
pwsh ./test.ps1 -OutputDirectory '/path/outside/repository/zeus-watch-tests'
```

Temporary certificates and classes stay outside the repository. The interface,
audio, and haptics are not covered by these checks; verify those on the watch.

## Install on a paired watch

Enable developer options and wireless debugging on the watch, then pair and
connect ADB using the addresses the watch shows. The pairing port can differ
from the connection port.

```powershell
adb pair WATCH_ADDRESS:PAIRING_PORT
adb connect WATCH_ADDRESS:DEBUG_PORT
adb -s WATCH_ADDRESS:DEBUG_PORT install -r '/path/outside/repository/zeus-watch/zeus-watch.apk'
```

Open **Zeus** from the watch app list. Rebuild and reinstall with the same
output directory when the station address or certificate changes.
