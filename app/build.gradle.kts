plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The openDAQ release the native libraries are built at, from the file the
// native build reads too. The SDK names its libraries after its major
// version, such as libopendaq-64-3.so, and the app loads its modules by name.
val opendaqVersion = System.getenv("OPENDAQ_VERSION")
    ?: rootDir.resolve("OPENDAQ_VERSION").readText().trim()

android {
    namespace = "io.opendaq.sensorserver"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.opendaq.sensorserver"
        // openDAQ and the Android device module are built for Android 8 (API 26)
        // and arm64 only.
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.2.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
        buildConfigField("String", "OPENDAQ_VERSION", "\"$opendaqVersion\"")
        buildConfigField("String", "OPENDAQ_MAJOR", "\"${opendaqVersion.substringBefore('.')}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // GnssTime, PhoneStatus and UsbReader, from the Android device module, which
    // they report to. The module is a separate repository, beside this one
    // unless the Gradle property androidDeviceModule or the environment
    // variable ANDROID_DEVICE_MODULE names another folder.
    val androidDeviceModule = (findProperty("androidDeviceModule") as String?)
        ?: System.getenv("ANDROID_DEVICE_MODULE")
        ?: rootDir.resolve("../opendaq-android-device-module").path
    sourceSets.getByName("main").java.srcDir(file(androidDeviceModule).resolve("android"))

    buildTypes {
        release {
            // Signed with the debug key, which is enough for installing by hand.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // The modules are loaded by name with dlopen, so they keep their
            // names and stay uncompressed in the APK.
            useLegacyPackaging = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}
