plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.robison.leitormp4"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.robison.leitormp4"
        minSdk = 24          // Android 7.0 ou superior
        targetSdk = 34
        versionCode = 8
        versionName = "2.1"
    }

    // Chave fixa: todas as versões têm a mesma assinatura, por isso
    // instalam por cima da anterior sem perder as playlists.
    signingConfigs {
        create("fixa") {
            storeFile = file("leitormp4.jks")
            storePassword = "leitormp4"
            keyAlias = "leitormp4"
            keyPassword = "leitormp4"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixa")
        }
        debug {
            signingConfig = signingConfigs.getByName("fixa")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Media3: leitor (ExoPlayer) + sessão de mídia (Bluetooth, carro, notificação, ecrã bloqueado)
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
}
