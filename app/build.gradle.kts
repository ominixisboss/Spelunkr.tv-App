plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "tv.spelunkr.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "tv.spelunkr.app"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            // Signed with the debug key so the release APK installs as-is; swap in a real keystore before publishing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.webkit)
}
