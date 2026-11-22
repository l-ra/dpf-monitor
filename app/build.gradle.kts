plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// A single version source for local builds, CI and GitHub Releases.
// A workflow_dispatch release may override it with -PappVersion=1.2.3.
val versionFile = rootProject.file("release/version.txt")
val appVersion = providers.gradleProperty("appVersion").orNull
    ?: if (versionFile.exists()) versionFile.readText().trim() else "0.9.0"
require(Regex("""\d+\.\d+\.\d+""").matches(appVersion)) { "Version must be MAJOR.MINOR.PATCH" }
val parts = appVersion.split(".").map { it.toInt() }
require(parts[1] < 100 && parts[2] < 100) { "Minor and patch must be below 100" }
val computedCode = parts[0] * 10000 + parts[1] * 100 + parts[2]

android {
    namespace = "cz.dpf.monitor"
    compileSdk = 35
    defaultConfig {
        applicationId = "cz.dpf.monitor"
        minSdk = 31
        targetSdk = 35
        versionCode = computedCode
        versionName = appVersion
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
