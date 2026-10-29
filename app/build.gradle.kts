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
        versionCode = 5
        versionName = "0.2"
    }

    signingConfigs {
        // CI signs with the repository's own key when it has one, so a new version installs over the old one.
        System.getenv("BUDI_KEYSTORE")?.let { keystore ->
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("BUDI_KEYSTORE_PASSWORD")
                keyAlias = "budi"
                keyPassword = System.getenv("BUDI_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
