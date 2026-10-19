import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.tapissync"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.tapissync"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.4"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/tapissync.jks")
            storePassword = "tapissync"
            keyAlias = "tapissync"
            keyPassword = "tapissync"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        // Ressources Android disponibles pour les captures d'écran faites par Robolectric.
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // org.json réel pour les tests JVM (celui d'Android n'existe qu'à l'exécution sur l'appareil).
    testImplementation("org.json:json:20240303")
    // Rendu des écrans sur la machine de compilation, pour vérifier le design.
    testImplementation("org.robolectric:robolectric:4.14.1")
}
