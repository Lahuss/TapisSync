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
        versionCode = 2
        versionName = "1.1"
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
}
