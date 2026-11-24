import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "es.bicilab.bicicam"
    compileSdk = 34

    defaultConfig {
        applicationId = "es.bicilab.bicicam"
        minSdk = 23
        targetSdk = 34
        versionCode = 38
        versionName = "0.6.0"
    }

    // La clave de firma vive fuera del código, en firma/firma.properties, que no se publica.
    // Con la misma clave, cada versión nueva se instala encima de la anterior en el Karoo.
    val firma = rootProject.file("firma/firma.properties").takeIf { it.exists() }?.let { file ->
        Properties().apply { file.inputStream().use { stream -> load(stream) } }
    }
    signingConfigs {
        if (firma != null) {
            create("bicicam") {
                storeFile = rootProject.file("firma/" + firma.getProperty("storeFile"))
                storePassword = firma.getProperty("storePassword")
                keyAlias = firma.getProperty("keyAlias")
                keyPassword = firma.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Las de pruebas van con la clave de pruebas: lo único firmado con la clave buena es la versión que se publica
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            signingConfig = signingConfigs.findByName("bicicam")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Igual que la que se publica, pero sin la dirección de actualizaciones (ver app/src/prueba)
        create("prueba") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    // En las pruebas, lo de Android que no se usa de verdad (el reloj, el registro) devuelve valores vacíos
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.hammerhead.karoo.ext)
    implementation(libs.androidx.core.ktx)
    implementation(libs.bundles.androidx.lifeycle)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.compose.ui)
    implementation(libs.androidx.glance.appwidget)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation("junit:junit:4.13.2")
}
