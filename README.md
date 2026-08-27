# Monita Android SDK

On-device vendor network call monitoring for Android apps. The SDK observes your app's outgoing vendor traffic (Firebase, Meta, AppsFlyer, Adjust, and any other vendor you configure), matches it against your remote Monita configuration, and reports events to your Monita workspace with the same schema as the Monita web monitoring script.

Version 2.0.0. Requires minSdk 23 (Android 6.0) and OkHttp 4.x.

## Design principles

- Never break the host app. Every capture path is exception contained. The SDK never mutates, re-issues, retries, or consumes a request or response.
- Passive observation only. Request bodies are snapshotted only when safe (never one-shot or duplex bodies, capped at 64KB). Response bodies are never read.
- Wire compatible with the Monita web script: same remote config, same payload schema, same warehouse columns.
- Reliable delivery: events persist to disk and are deleted only after the collect endpoint confirms receipt with a 2xx.
- Privacy first: no fingerprinting, no advertising identifiers, no credential harvesting. Captured parameters are capped and filtered through your configured exclusion lists before sending.

## Installation

The SDK is published through JitPack.

In `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

In your app module:

```kotlin
dependencies {
    implementation("com.github.rnadigital:monita-android-sdk:2.0.0")
}
```

## Quick start

```kotlin
import ai.monita.sdk.Monita

Monita.initialize(context, token = "dom_xxxxxxxxxxxxxxxxxxxxxxxx")
```

Or with options:

```kotlin
Monita.initialize(context, MonitaConfig.Builder("dom_...").debugLogging(false).build())
```

Your token is on the property settings page in your Monita workspace. It is a public identifier, not a secret.

### Automatic initialization from the manifest

Add the token as manifest meta-data and the SDK initializes itself at app startup (via androidx.startup):

```xml
<application>
    <meta-data
        android:name="ai.monita.sdk.TOKEN"
        android:value="dom_xxxxxxxxxxxxxxxxxxxxxxxx" />
</application>
```

If you prefer a ContentProvider-free setup, remove the startup entry and call `Monita.initialize` yourself in `Application.onCreate`:

```xml
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    tools:node="remove" />
```

## Integration modes

### Mode 1: manual interceptor (works everywhere)

Add `MonitaInterceptor` to any OkHttp client you build:

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(MonitaInterceptor())
    .build()
```

The interceptor sees the request, the response status, and timing. It is safe to add before `Monita.initialize` is called; it simply passes traffic through until the SDK is ready.

### Mode 2: automatic instrumentation (covers third-party SDK clients)

Most vendor SDKs build their own OkHttp clients internally, so you cannot add an interceptor by hand. The optional companion build plugin weaves `OkHttpClient.Builder.build()` at compile time so every client in the app, including clients built inside third-party libraries that use unshaded OkHttp, gets the Monita interceptor automatically.

See the `monita-android-sdk-adaptor` repository for setup. The manual interceptor works without it.

## API reference

All methods are safe from any thread and are no-ops before initialization.

| Method | Description |
| --- | --- |
| `Monita.initialize(context, token)` | Start the SDK with default options. |
| `Monita.initialize(context, config)` | Start with a `MonitaConfig` (collect endpoint, config endpoint, debug logging). |
| `Monita.setCustomerId(id)` | Attach your customer id to events (`cid`). Null clears it. |
| `Monita.setSessionId(id)` | Override the session id. Null returns to automatic 30 minute rotation. |
| `Monita.setConsent(consent)` | Override consent auto-detection with an explicit consent string. |
| `Monita.setConsentProvider { ... }` | Dynamic consent source, consulted on every batch. Wins over `setConsent`. |
| `Monita.setScreen(name)` | Current screen name, reported in the `u` and `p` fields. |
| `Monita.setEventFilter { payload -> ... }` | Gate every outgoing event; return false to drop it. |
| `Monita.setEventExtractor { vendor, data -> ... }` | Custom event name extraction, consulted before the config template. |
| `Monita.send(vendor, event, data)` | Manual event. Requires `allowManualMonitoring` in your remote config. |
| `Monita.optOut()` / `Monita.optIn()` | Persistent capture opt out. Opting out clears queued events. |
| `Monita.flush()` | Attempt an immediate upload of queued events. |
| `Monita.refreshConfig()` | Re-fetch the remote config, bypassing the CDN cache. |
| `Monita.setDebugLogging(true)` | Verbose logs and unbatched delivery (one POST per event). Default off. |
| `Monita.version` | The SDK version string. |

`MonitaConfig.Builder` options:

| Option | Default | Description |
| --- | --- | --- |
| `collectEndpoint(url)` | `https://collect.monita.ai/api/v1` | Full collect URL, for customer reverse proxies. |
| `configEndpoint(url)` | Derived from the token | Full config JSON URL for the property. |
| `debugLogging(enabled)` | `false` | Verbose logging and unbatched delivery. |

## Consent behavior

The SDK reports a consent string with every batch (the `cn` field) so your Monita workspace can segment monitored traffic by consent state.

By default the SDK reads the IAB strings that consent management platforms write to the default SharedPreferences, in this priority order:

1. `IABTCF_TCString` (TCF v2)
2. `IABGPP_HDR_GppString` (GPP)
3. `IABUSPrivacy_String` (US Privacy)

The value is re-read on every batch, and a preference change listener makes sure a consent update never mixes into an older batch.

Overrides:

- `Monita.setConsent("...")` replaces auto-detection with a fixed string.
- `Monita.setConsentProvider { cmp.currentConsentString() }` is consulted dynamically and wins over both.

The SDK does not block capture on consent by default, matching the web monitoring script. To gate capture on a CMP decision, wire the CMP callback into the event filter:

```kotlin
Monita.setEventFilter { payload -> myCmp.isMonitoringAllowed() }
```

Returning false drops the event before it is queued or sent.

## Coverage and limitations

| Traffic | Covered |
| --- | --- |
| OkHttp clients with `MonitaInterceptor` added | Yes |
| OkHttp clients in third-party SDKs (unshaded OkHttp, with the instrumentation plugin) | Yes |
| Firebase, Meta, AppsFlyer, Adjust, Branch and other vendor SDKs that use unshaded OkHttp | Yes, with the instrumentation plugin |
| `HttpURLConnection` traffic | No |
| Cronet traffic | No |
| WebView traffic | No |
| SDKs that shade or embed a renamed copy of OkHttp | No |
| Raw sockets, gRPC over its own transport | No |

Body capture limits: request bodies are read only when the body is replayable (not one-shot, not duplex), textual, and declares a bounded length of at most 64KB. Streaming bodies with unknown length, larger bodies, and binary bodies contribute URL parameters only. Response bodies are never read.

**Multi-process apps.** The SDK runs only in the app's default process (the process whose name equals the package name); initialization in any other process is a logged no-op. Network traffic in non-default processes is not monitored. This keeps the persistent queue single-writer and corruption free.

## Reliability and delivery

- Events persist in a JSON lines queue under the app's private no-backup files directory and are deleted only after the collect endpoint returns a 2xx. Captured payloads and cached config are excluded from Android Auto Backup, so they never reach cloud backups and stale events never restore onto a new device. The visitor id lives in SharedPreferences and may survive a backup and restore; treat a restored device as the same install.
- Uploads batch up to 50 events (60KB) per POST, flushing at 10 events, 2 seconds after the first queued event, or when the app moves to the background.
- Retries use exponential backoff with jitter (5 seconds base, 10 minute cap) and wait for network availability.
- Queue caps: 500 events or 2MB; the oldest events are dropped first.
- Batches the collect endpoint permanently rejects (4xx other than 408 and 429) are dropped rather than retried forever; every drop is counted persistently and logged with the running total.
- A circuit breaker stops capture for the rest of the process if more than 100 events are captured within 6 seconds.

## Documented deviations from the web monitoring script

The SDK ports the web monitoring script's semantics exactly, with these deliberate exceptions:

- A parameter value with a malformed percent escape (or one that is not valid UTF-8) is kept raw instead of dropping the whole event, which is what the web script's decodeURIComponent failure does.
- Event templates require well formed double braces; the web script's lenient handling of a single stray brace is not reproduced.
- An event name that is only whitespace is kept as a value; the web script's loose coercion treats it as missing.
- Permanently rejected batches are dropped with a persistent counter (see above); the web script has no persistent queue and simply loses the beacon.

## Troubleshooting

**No events arrive.**
Check that the token is correct and that your property's monitoring configuration is published. Until the first successful config fetch, monitoring stays off. Enable `Monita.setDebugLogging(true)` and watch logcat with the tag `Monita`.

**Vendor traffic is not detected.**
Confirm the vendor's URL patterns in your Monita configuration match the request URLs, and check the coverage table above; traffic that does not go through OkHttp is not visible to the SDK.

**Events appear delayed.**
Delivery is batched. Call `Monita.flush()` to force an immediate attempt, or enable debug logging, which ships every event immediately in its own POST.

**Monitoring stopped by itself.**
The remote kill switch (a paused or removed property) disables capture, and the circuit breaker trips when capture volume is anomalous. Both are visible in debug logs.

**ProGuard / R8.**
Consumer rules ship inside the library; no extra configuration is needed.

## Copyright

Copyright RNA Digital PTY LTD
