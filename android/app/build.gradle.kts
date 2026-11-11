plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "xyz.critlauncher.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "xyz.critlauncher.app"
        minSdk = 24
        targetSdk = 34
        // every CI build gets a higher number so it installs over the previous one
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.7.1-android" + (System.getenv("GITHUB_RUN_NUMBER")?.let { "+$it" } ?: "")
        ndk {
            // 32-bit and 64-bit ARM phones in one APK
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    // CI passes a persistent signing key through the environment (see .github/workflows/android.yml) so every
    // build is signed the same way and can be installed as an update. Without it, Gradle's debug key is used.
    val critKeystore = System.getenv("CRIT_KEYSTORE")
    if (critKeystore != null) {
        signingConfigs {
            create("crit") {
                storeFile = file(critKeystore)
                storePassword = System.getenv("CRIT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CRIT_KEY_ALIAS") ?: "crit"
                keyPassword = System.getenv("CRIT_KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        debug {
            if (critKeystore != null) signingConfig = signingConfigs.getByName("crit")
        }
        release {
            isMinifyEnabled = false
            if (critKeystore != null) signingConfig = signingConfigs.getByName("crit")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // The engine's JRE packs are already xz-compressed; the JVM and native bridges are loaded by path, so the
    // .so files must be extracted to the app's native library dir.
    androidResources { noCompress += listOf("xz", "jar") }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "**/libc++_shared.so"
        }
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"
        buildPython("python3")
        pip {
            install("Pillow")
        }
    }
}

// The backend and UI live at the repo root; copy them in so there is a single source of truth.
val repoRoot = rootProject.projectDir.parentFile
val syncCritSources = tasks.register<Sync>("syncCritSources") {
    from(File(repoRoot, "crit_launcher")) {
        into("crit_launcher")
        exclude("**/__pycache__/**", "**/*.pyc")
    }
    into(layout.projectDirectory.dir("src/main/python"))
}
val syncCritResources = tasks.register<Sync>("syncCritResources") {
    from(File(repoRoot, "index.linux.html"))
    from(File(repoRoot, "assets")) { into("assets") }
    into(layout.projectDirectory.dir("src/main/assets/crit_res"))
}
tasks.configureEach {
    if (name == "preBuild") {
        dependsOn(syncCritSources, syncCritResources)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // unpacking the engine's .tar.xz JRE packs
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")
}
