plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.sammeeeeeeee.budi"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.sammeeeeeeee.budi"
        minSdk = 33
        targetSdk = 36
        versionCode = 6
        versionName = "0.3"
    }

    signingConfigs {
        // Committed on purpose: every build signs the same way, so each version installs over the last.
        create("release") {
            storeFile = file("budi.jks")
            storePassword = "budibudi"
            keyAlias = "budi"
            keyPassword = "budibudi"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.2")
    testImplementation("junit:junit:4.13.2")
}
