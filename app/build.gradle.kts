plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "cz.dpf.monitor"
    compileSdk = 35
    defaultConfig {
        applicationId = "cz.dpf.monitor"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    signingConfigs {
        create("production") {
            val keyPath = System.getenv("ANDROID_KEYSTORE_FILE")
            if (!keyPath.isNullOrBlank()) {
                storeFile = file(keyPath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("debug") { isMinifyEnabled = false }
        getByName("release") {
            isMinifyEnabled = false
            if (!System.getenv("ANDROID_KEYSTORE_FILE").isNullOrBlank()) signingConfig = signingConfigs.getByName("production")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies { testImplementation("junit:junit:4.13.2") }
