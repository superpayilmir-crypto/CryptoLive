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

    // Ключ подписи НЕ хранится в коде: на GitHub он расшифровывается из
    // app/release.keystore.enc паролем из секрета SIGNING_PASSWORD.
    // Локально без пароля сборка подписывается отладочным ключом.
    val ksPath = System.getenv("SIGNING_KEYSTORE")
    val ksPass = System.getenv("SIGNING_PASSWORD")
    val hasKey = !ksPath.isNullOrBlank() && !ksPass.isNullOrBlank() && file(ksPath).exists()

    signingConfigs {
        if (hasKey) {
            create("app") {
                storeFile = file(ksPath!!)
                storePassword = ksPass
                keyAlias = "cryptolive"
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasKey) signingConfigs.getByName("app") else signingConfigs.getByName("debug")
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
