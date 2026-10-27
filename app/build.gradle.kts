import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// The Play upload key lives outside the repo; keystore.properties (git-ignored) says where.
// Without it, release builds fall back to the debug key so anyone can still build.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

android {
    namespace = "tv.spelunkr.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.oozi.spelunkr"
        minSdk = 24
        targetSdk = 37
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("upload") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
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
    implementation(libs.androidx.tvprovider)
}
