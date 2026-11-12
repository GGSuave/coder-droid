import java.util.Properties

plugins {
    id("com.android.application")
}

// Credenciais de assinatura ficam fora do codigo (keystore.properties, na raiz do projeto).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.codedroid.java"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.codedroid.java"
        minSdk = 26
        // Mantido em 28 de proposito: apps com targetSdk >= 29 nao podem dar exec em
        // binarios que estejam no armazenamento interno (W^X). Mesma estrategia do Termux.
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Nao ha codigo Kotlin significativo para ofuscar; o peso do APK sao os binarios.
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    // Um APK por arquitetura: o rootfs e o JDK sao binarios nativos.
    flavorDimensions += "abi"
    productFlavors {
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters += "arm64-v8a" }
        }
        create("x64") {
            dimension = "abi"
            ndk { abiFilters += "x86_64" }
        }
    }

    packaging {
        // Necessario para que libproot.so seja extraido para nativeLibraryDir (executavel).
        jniLibs { useLegacyPackaging = true }
    }

    androidResources {
        // Nao comprimir: o .bin ja e um tar.gz e queremos openFd() para medir progresso.
        noCompress += listOf("bin")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.28.0")
    // Ponte WebView <-> area de transferencia do Android (restrita por origem)
    implementation("androidx.webkit:webkit:1.18.0")
}

