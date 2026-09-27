plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cryptoticker.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cryptoticker.app"
        minSdk = 26
        targetSdk = 35
        // Номер сборки GitHub — каждая новая версия ставится поверх старой
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        versionCode = 100 + run
        versionName = "1.0.$run"
    }

    signingConfigs {
        create("app") {
            storeFile = file("cryptolive.keystore")
            storePassword = "cryptolive"
            keyAlias = "cryptolive"
            keyPassword = "cryptolive"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("app")
        }
        debug {
            signingConfig = signingConfigs.getByName("app")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
