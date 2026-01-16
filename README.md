# Monita SDK

Monita SDK provides powerful monitoring and analytics capabilities for your Android applications, including request monitoring, analytics integration, and performance tracking.

## Installation

To integrate Monita SDK into your Android project, follow the steps below.

### Step 1: Add the JitPack Repository

Add the following lines to your root `settings.gradle.kts`:

```gradle
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io")  }
    }
}
```

### Step 2: Add the Dependencies

Include the Monita SDK and the Monita Adapter Library in your **module-level** `build.gradle.kts` or `build.gradle` file:

```gradle
dependencies {
    implementation ("com.github.rnadigital.monita-android-sdk:monita-android-sdk:v1.10.0")
    implementation ("com.github.rnadigital.monita-android-sdk:monita-adapter-library:v1.10.0")
    byteBuddy ("com.github.rnadigital.monita-android-sdk:monita-adapter-library:v1.10.0")
}
```

### Step 3: Byte Buddy Plugin Integration

Add the Byte Buddy Gradle plugin to your project by including the following in your `build.gradle.kts` or `build.gradle` file:

```gradle
plugins {
    id("net.bytebuddy.byte-buddy-gradle-plugin") version "1.15.5"
}
```

### Step 4: Sync the Project

Once you've added the dependencies, sync your project with Gradle by clicking **"Sync Now"** in Android Studio or running:

```bash
./gradlew build
```

## Usage

After successful integration, you can initialize Monita SDK in your application class:

```kotlin
import android.app.Application
import com.rnadigital.monita_android_sdk.MonitaSDK

class MyApplication : Application() {
    val token = "fe041147-0600-48ad-a04e-d3265becc4eb"

    override fun onCreate() {
        super.onCreate()
        MonitaSDK.Builder(this)
            .enableLogger(true)
            .setToken(token)
            .setBatchSize(10)
            .setCustomerId("123456")
            .setConsentString("Granted")
            .setSessionId("123456")
            .setAppVersion("1.0")
            .build {
                // Callback when initialization is complete
            }
    }
}
```

## Features

- Automatic request monitoring
- Analytics integration (Google, Facebook, Firebase)
- Performance tracking
- Easy to integrate

## Troubleshooting

If you encounter any issues:

1. Ensure that your GitHub repository has public access, or configure private access with a GitHub token.
2. Clear Gradle cache and sync again:
   ```bash
   ./gradlew clean --refresh-dependencies
   ```
3. Visit [JitPack.io](https://jitpack.io) and verify build status for your project.

## License

This project is licensed under the [MIT License](LICENSE).

## Contact

For any issues or support, please reach out to [support@monita.com](mailto:support@monita.com).


## Architecture

The Monita monitoring solution is composed of three separate libraries that work in tandem to capture, process, and transmit critical application events. Here's a breakdown of each component:

### 1. Monita SDK (monita-android-sdk)
- **Role:**  
  Functions as the core engine that processes, batches, and sends the captured data to the Monita server.

- **Responsibilities:**  
  - **Core Initialization & Configuration:**  
    Sets up monitoring configurations (such as filtering rules, vendor settings, and other metadata) and handles overall initialization through its Builder pattern.
  - **Data Processing:**  
    Applies filtering, transforms the intercepted event data, and formats it into standardized payloads.
  - **Batching & Persistence:**  
    Batches multiple events together and manages local storage (using Room) to ensure reliability in scenarios of network loss.
  - **Transmission:**  
    Schedules and dispatches batched data to the Monita server, ensuring efficient use of network resources.
  - **Centralized Logging:**  
    Provides logging and debugging tools that aid in monitoring the system's performance and troubleshooting issues.

### 2. Monita Adapter (monita-adapter)
- **Role:**  
  Specifically handles the interception of network calls made using OkHttp.

- **Responsibilities:**  
  - **OkHttp Instrumentation:**  
    Uses Byte Buddy to instrument OkHttp methods like `newCall`, `execute`, and `enqueue`.
  - **Network Request Capture:**  
    Intercepts and processes HTTP requests and responses when using OkHttp.
  - **Initialization:**  
    Provides the necessary setup to activate network call monitoring.

- **Key Point:**  
  This module is only required if your application uses OkHttp for network requests.

### 3. Monita Adapter Library (monita-adapter-library)
- **Role:**  
  Serves as the dynamic instrumentation module for vendor SDKs, using Byte Buddy to intercept and capture events at runtime.

- **Responsibilities:**  
  - **Vendor SDK Instrumentation:**  
    Contains dedicated Byte Buddy plugins for intercepting calls from various SDKs:
    - Firebase Analytics
    - Adobe Analytics
    - Facebook Events
    - Google Ads
  - **Method Interception:**  
    Uses advice classes to capture specific method calls (e.g., Firebase's `logEvent` or Adobe's `trackAction`).
  - **Data Forwarding:**  
    Forwards captured events to the Monita SDK for processing.

- **Key Point:**  
  Works independently of the networking library used—vendor events are captured by instrumenting those SDKs directly.

Together, these three components form a modular and robust monitoring framework:
- **Monita SDK** processes and manages the captured data.
- **Monita Adapter** handles OkHttp network call interception.
- **Monita Adapter Library** captures vendor SDK events.

This architecture allows for comprehensive monitoring without requiring changes to your existing codebase, whether you're using OkHttp for networking or integrating with various third-party analytics providers.

