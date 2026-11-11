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
        versionCode = 1
        versionName = "1.7.1-android"
        ndk {
            // 32-bit and 64-bit ARM phones in one APK
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
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
}
