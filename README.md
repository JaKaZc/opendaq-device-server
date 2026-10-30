# openDAQ Device Server

An Android app that serves the phone's sensors as an
[openDAQ](https://opendaq.com) device on the network, and nothing else. It has
no plots and no device browser: openDAQ Studio on a computer, or any other
openDAQ client, is the client. The server keeps running in the background
while the app is closed and the screen is off.

The app runs on Android 8 (API 26) or newer, on arm64 phones and tablets
only. Clients on any other system reach the phone over the network: openDAQ
Studio, or a program of your own in Python, C++ or C# with the openDAQ SDK
3.40.3 (see "Using the phone from other projects" in the README of
opendaq-android-device-module).

The app runs the openDAQ SDK itself, built for Android arm64, with three
modules:

| Module | From | Purpose |
|---|---|---|
| AndroidDeviceModule | opendaq-android-device-module | The phone itself as the root device (`android://`), named after the phone, with its sensors as channels: Microphone, Accelerometer, Gyroscope, Magnetometer, Orientation, Proximity, Light, Camera, System, USB and more |
| UniversalFunctionBlocksModule | opendaq-universal-function-blocks, optional | The function blocks the phone runs: Universal renderer, Sound level meter, Spectrum, Vibration and Dashboard |
| OpenDAQNativeStreamingServerModule | the openDAQ SDK | Serves the instance over openDAQ's native protocol on TCP port 7420 |

## The openDAQ phone projects

This repository is one of four that work together, but each one stands on
its own: take only the parts you need, and build your own around them.
"Needs" says what a repository cannot do without.

| Repository | What it is | Needs |
|---|---|---|
| [opendaq-android-device-module](https://github.com/JaKaZc/opendaq-android-device-module) | openDAQ module, **Android only**: the phone's sensors and state as one device, `android://` | The openDAQ SDK and the Android NDK |
| [opendaq-universal-function-blocks](https://github.com/JaKaZc/opendaq-universal-function-blocks) | openDAQ module, UniversalFunctionBlocksModule: Universal renderer, Level, Spectrum, Vibration, Sound level meter and Dashboard, for any platform | The openDAQ SDK |
| **opendaq-device-server** (this one) | Android app that serves the phone as an openDAQ device on the network | The Android device module. The function blocks module is optional |
| [opendaq-studio-desktop](https://github.com/JaKaZc/opendaq-studio-desktop) | openDAQ Studio, the desktop client for macOS and Windows, for any openDAQ device | Nothing else. The function blocks module is optional |

```
 Android phone: this app                              Computer: opendaq-studio-desktop
 ┌──────────────────────────────────────────┐         ┌──────────────────────────────────────────┐
 │ SensorServerService (foreground)         │         │ openDAQ Studio                           │
 │  libsensor_server.so (JNI)               │         │  openDAQ instance, native client         │
 │   openDAQ instance                       │   TCP   │  UniversalFunctionBlocksModule           │
 │    android:// root device <- Android     │   7420  │                                          │
 │      device module                       │ ──────▶ │  adds daq.nd://<phone address>           │
 │    function blocks <- universal          │         │                                          │
 │      function blocks module              │   mDNS  │  finds the phone by discovery            │
 │    native streaming server               │ ──────▶ │                                          │
 │  Dashboard web page, TCP 8090            │         │                                          │
 │  NTP client                              │ ◀────── │  NTP time server, UDP 12300              │
 └──────────────────────────────────────────┘         └──────────────────────────────────────────┘
```

All four are built against the same openDAQ SDK release, **v3.40.3**.
openDAQ's native protocol does not connect a client of one release to a server
of another, so the phone and the computer must use the same release.

## Use only what you need

- **The Android device module is required.** The app is its host: without
  [opendaq-android-device-module](https://github.com/JaKaZc/opendaq-android-device-module) there is
  nothing to serve.
- **The function blocks module is optional.** When
  [opendaq-universal-function-blocks](https://github.com/JaKaZc/opendaq-universal-function-blocks)
  is not beside this repository, the app is built without it. The phone then
  serves its sensors without the Universal renderer, the derived signals
  (Sound level, Sound spectrum, Vibration) and the Dashboard.
- **Any openDAQ client.** openDAQ Studio
  ([opendaq-studio-desktop](https://github.com/JaKaZc/opendaq-studio-desktop)) is one client. A
  Python, C++ or C# program with the openDAQ SDK 3.40.3 is another; the
  Android device module's README shows one.
- **Your own app.** This app is one way to host the Android device module.
  Its native side, `native/server/src/server.cpp`, is short: it loads the
  modules, makes the phone the root device, adds function blocks and starts
  openDAQ's native streaming server. Your own app can do the same with other
  servers, such as openDAQ's OPC UA server, or with modules of your own: add
  their libraries to `tool/build_native.sh` and to `NativeServer.modules`, and
  the function blocks to add at start to `NativeServer.functionBlocks`.

## How it works

1. **The service.** Tapping **Start the server** starts `SensorServerService`,
   a foreground service with a notification. It loads the native library
   `libsensor_server.so` and starts openDAQ through JNI.
2. **The instance.** The native library creates one openDAQ instance, loads
   the three modules from the app's libraries, and makes the phone the root
   device: `android://?Microphone=…&Camera=…&Dashboard=true`, named after the
   phone, with the phone's serial number.
3. **The device's own blocks.** The phone device adds Sound level, Sound
   spectrum and Vibration blocks, connected to its microphone and
   accelerometer, and a Dashboard. The app then adds one **Universal
   renderer** (`UniversalRenderer`) to the root device
   (`NativeServer.functionBlocks`). A client that adds the phone therefore
   finds these blocks already there.
4. **The server.** The native streaming server listens on port 7420, and the
   instance announces itself with mDNS under the phone's name. A client finds
   it by discovery, or adds it with the connection string the app shows, such
   as `daq.nd://192.168.1.197`.
5. **The Kotlin helpers.** `GnssTime`, `PhoneStatus` and `UsbReader`, from the
   sensor module's `android/` folder, report the satellite time, the phone's
   state and USB devices to the module.
6. **Stopping.** **Stop** ends the server. **Quit** stops it and ends the
   app's process too.

A client that connects gets the whole device: it reads the signals, changes
properties, switches channels on and off, connects the function blocks' input
ports and reads their outputs. The phone device's channels, time and
calibration are described in the README of opendaq-android-device-module, and
the function blocks in the README of opendaq-universal-function-blocks.

## Installing

Download `openDAQ-Device-Server-<version>-arm64.apk` from the
[Releases](https://github.com/JaKaZc/opendaq-device-server/releases) on the phone, or build it
yourself (see Building). The app is not in Google Play, so Android installs
it only after you allow it:

1. Open the downloaded APK. Android asks whether the browser or file manager
   you opened it from may install unknown apps. Allow it in the settings page
   it opens, then go back.
2. Tap **Install**. Google Play Protect may say that it does not know the
   app or offer to scan it. Choose to scan or to install anyway.

Or install it from a computer over USB, with USB debugging switched on in the
phone's developer options:

```bash
adb install -r openDAQ-Device-Server-1.2.0-arm64.apk
```

The app needs Android 8 or newer on an arm64 phone or tablet.

### How releases are made

Every release is built on a Mac from this repository's sources and its two
sibling repositories, with `tool/build_native.sh` and
`./gradlew assembleRelease` as described in Building, against openDAQ SDK
3.40.3. The APK is signed with the Android debug key of the computer that
builds it, not with a key from Google Play. This decides how updates work:

- **A newer release installs over the installed app** and keeps its
  settings and permissions, because the releases share one signing key.
- **An APK you build yourself** is signed with your computer's debug key,
  which differs. Android refuses to install it over a release, and a release
  over it. Uninstall the app first; its settings and permissions are lost.

Each release lists the APK's SHA-256 checksum in `SHA256SUMS.txt`. Check the
download against it before you install it:

```bash
shasum -a 256 -c SHA256SUMS.txt --ignore-missing
```

A release of the Device Server and a release of openDAQ Studio work together
when both use the same openDAQ SDK release, as their release notes state.

## Using it

1. Install the app (see Installing) and open it.
2. Tap **Start the server**. Android asks for the microphone and for
   notifications. Without the microphone, its channel is inactive and the
   other sensors are served. To serve the camera as JPEG pictures too, tick
   **Include the camera** first, and Android then asks for the camera too. It
   is off by default, because a running camera keeps Android's privacy
   indicator on and uses more battery.
3. On the computer, add the phone in openDAQ Studio, from device discovery or
   with the connection string the app shows.

The app can be closed. The server runs until **Stop** is tapped in the app or
in its notification. **Quit the app**, and **Quit** in the notification, stop
the server and end the app's process.

### The Dashboard

The phone serves a web page of its main signals: open the address the app
shows under Dashboard, such as `http://192.168.1.39:8090/`, in any browser on
the network. It shows the sound level and spectrum, the vibration, the
orientation, the battery, the processor and, where the phone has a
barometer, the air pressure. Its Settings view changes the phone's settings
after asking for the PIN the app shows. The PIN is new at every start.
Connect more signals to the Dashboard's input ports from openDAQ Studio to add
panels.

### The Universal renderer

In openDAQ Studio, connect the renderer's input ports to the phone's signals,
set its properties and show its output signal `Plot`. The block lays out the
plot on the phone and sends it as plot data, about 2 to 10 kB a frame, so the
computer receives a finished plot instead of the raw samples. With its
`WebServer` property on, it also serves the plot to any browser at the
address in `WebAddress`, such as `http://192.168.1.39:8080/`. It shows the
camera too when the camera's `Image` signal is connected.

### Starting it with adb

```bash
adb shell am start-foreground-service -n io.opendaq.sensorserver/.SensorServerService
```

```bash
adb shell am start-service -n io.opendaq.sensorserver/.SensorServerService -a io.opendaq.sensorserver.STOP
```

The action `io.opendaq.sensorserver.QUIT` stops the server and ends the
process. Only callers that hold Android's DUMP permission may start the
service. adb's shell holds it and other apps do not, so no other app can
switch on the microphone. The microphone permission still has to be given
once in the app.

## Running in the background

Android stops an app's background work unless it runs as a foreground service
with a visible notification. `SensorServerService` is one, of the types
`connectedDevice` (serving on the network), `microphone`, `camera` when the
camera is included, and `location` while the phone device uses GNSS time.
While it runs, it holds:

- a partial wake lock, so the processor stays awake and the sensors keep
  delivering with the screen off,
- a low-latency Wi-Fi lock, so the network stays up,
- the Wi-Fi multicast lock, so the mDNS announcement and queries get through.

These cost battery, so long sessions should run on a charger. Android also
limits the microphone and the camera in the background: a foreground service
may use them only if it was started while the app was on screen, which
tapping Start does. A client that switches on the Camera channel of a server
started without the camera gets an error status on the channel, because the
service lacks the camera type. Include the camera before starting instead.

## Notes

- **Who can connect.** The native server has no password. Anyone on the same
  network can connect, so run it only on a network you trust. The Dashboard
  and the renderer's web page use plain HTTP.
- **Without a network.** openDAQ's mDNS discovery server refuses to start
  without a network interface. The server then starts without the
  announcement and is added by connection string.
- **One server per phone.** Only one app on the phone can listen on port 7420
  at a time.
- **Time.** The device's TimeSource property chooses the time of every
  sample: the phone's clock, an NTP server or the satellite receiver. openDAQ
  Studio on the computer points the phone at its own NTP server when it
  connects. For GNSS time, the app asks for the location permission when it is
  on screen.
- **Package name.** The app's package is `io.opendaq.sensorserver`, and some
  class and library names say "sensor server", the app's first name. They are
  kept so that an installed app updates in place.

## Requirements

| What | Version | Notes |
|---|---|---|
| A Mac or Linux computer | | The native build is a POSIX shell script. Gradle runs anywhere |
| [opendaq-android-device-module](https://github.com/JaKaZc/opendaq-android-device-module) | required | Cloned beside this repository, or named by `ANDROID_DEVICE_MODULE` |
| [opendaq-universal-function-blocks](https://github.com/JaKaZc/opendaq-universal-function-blocks) | optional | Cloned beside this repository, or named by `UNIVERSAL_FUNCTION_BLOCKS`; without it the app has no function blocks |
| Android Studio | with Android SDK 36 | For the SDK, the NDK and the Java that Gradle needs |
| Android NDK | 28.2 (the version it is built and tested with) | Android Studio's SDK Manager, under SDK Tools |
| Java | 17 or newer | Android Studio's own Java works (see below) |
| CMake | 3.24 or newer | `brew install cmake` on macOS |
| Ninja | | `brew install ninja` on macOS |
| git | | To clone the openDAQ SDK |
| Network access | | On the first build, for the SDK, its dependencies and Gradle |
| ccache | optional | Used when installed; repeated builds are much faster |

The phone needs Android 8 (API 26) or newer, arm64.

## Building

Clone the app and the Android device module side by side, and the function
blocks too if you want them:

```bash
git clone https://github.com/JaKaZc/opendaq-device-server.git
git clone https://github.com/JaKaZc/opendaq-android-device-module.git
git clone https://github.com/JaKaZc/opendaq-universal-function-blocks.git   # optional
cd opendaq-device-server
```

```bash
tool/build_native.sh
```

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleRelease
```

`tool/build_native.sh`:

1. clones the openDAQ SDK into `build/native/openDAQ`, checks out `v3.40.3` and
   applies the patch in `patches/`. The patch replaces one `dynamic_cast` that
   fails across libraries on Android; the Android device module's README explains it,
2. builds the SDK for Android arm64 with only the native streaming server
   module,
3. builds AndroidDeviceModule from its sibling repository,
   UniversalFunctionBlocksModule when its repository is there, and the JNI
   library in `native/server`, all against that SDK,
4. copies the stripped libraries and `libc++_shared.so` into
   `app/src/main/jniLibs/arm64-v8a`.

The first run takes about ten minutes. Later runs only rebuild what changed.
Gradle then builds `app/build/outputs/apk/release/app-release.apk`. Gradle
finds the Android SDK through `ANDROID_HOME`, or through a `local.properties`
file with `sdk.dir=`, which Android Studio writes when it opens the project.
The release APK is signed with the debug key, which is enough for installing
by hand:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

| Variable | Default | Meaning |
|---|---|---|
| `ANDROID_DEVICE_MODULE` | `../opendaq-android-device-module` | The Android device module's sources. Gradle also reads the property `-PandroidDeviceModule=` |
| `UNIVERSAL_FUNCTION_BLOCKS` | `../opendaq-universal-function-blocks` | The function blocks module's sources |
| `OPENDAQ_SOURCE` | `https://github.com/openDAQ/openDAQ.git` | Where to clone the SDK from; a local clone is faster |
| `SDK_REF` | `v3.40.3` | The SDK release. It must match openDAQ Studio's |
| `ANDROID_NDK` | the newest NDK in the Android SDK | The NDK |
| `CMAKE`, `NINJA` | `cmake`, `ninja` | The tools, when they are not on the PATH |

To publish a signed release, add a release signing configuration of your own
to `app/build.gradle.kts`, and keep the keystore out of the repository.

## Layout

| Path | |
|---|---|
| `native/server/src/server.cpp` | The JNI library: the openDAQ instance, its modules, the phone device, the function blocks, the server and the status for the notification |
| `app/src/main/kotlin/.../SensorServerService.kt` | The foreground service: locks, notification, starting and stopping openDAQ |
| `app/src/main/kotlin/.../MainActivity.kt` | The control screen: Start and Stop, connection strings, sensors, clients and the Dashboard's address and PIN |
| `app/src/main/kotlin/.../NativeServer.kt` | The JNI functions, the modules, the phone device's options and the function blocks added at start |
| `app/src/main/kotlin/.../Phone.kt` | The phone's identity and local addresses |
| `app/src/main/AndroidManifest.xml` | Permissions, the service and its foreground types |
| `patches/` | The SDK change Android needs |
| `tool/build_native.sh`, `tool/prepare_sdk.sh` | The native build |

## License

Apache License 2.0, the same as the openDAQ SDK. See [LICENSE](LICENSE).

This is an independent project built on the openDAQ SDK. openDAQ is a
trademark of its respective owners.
