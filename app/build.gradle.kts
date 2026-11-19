plugins {
    id("com.android.application")
}

android {
    namespace = "com.jdsoporte.pianomidi"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jdsoporte.pianomidi"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-monitor-midi"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
