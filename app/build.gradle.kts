plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mouad.doublage"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mouad.doublage"
        minSdk = 29
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "v" + (System.getenv("GITHUB_RUN_NUMBER") ?: "1")
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true   // exigé par NewPipeExtractor sous Android 13
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        disable += setOf("MissingTranslation", "SetTextI18n", "InvalidPackage", "GradleDependency", "OldTargetApi")
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    // V9 : retrouver la vidéo YouTube et ses sous-titres (sans clé ni compte)
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.4")
    testImplementation("junit:junit:4.13.2")
}
