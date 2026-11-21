plugins {
    id("com.android.application")
}

android {
    namespace = "com.jdsoporte.pianomidi"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.jdsoporte.pianomidi"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2-piano-B"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.oboe:oboe:1.11.0")
}
