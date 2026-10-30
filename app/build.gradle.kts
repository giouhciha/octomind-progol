import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
}

android {
    namespace = "mx.octomind.progol"
    compileSdk = 36

    defaultConfig {
        applicationId = "mx.octomind.progol"
        minSdk = 26
        targetSdk = 36
        versionCode = 50
        versionName = "0.10.0"

        testInstrumentationRunner = "mx.octomind.progol.IntegrationInstrumentation"
    }

    signingConfigs {
        create("release") {
            val props = Properties().apply {
                FileInputStream(rootProject.file("keystore.properties")).use { load(it) }
            }
            storeFile = rootProject.file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("qa-assets"))
}

val debugArtifactVersion = android.defaultConfig.versionName!!.replace(".", "-")
val copyVersionedDebugApk = tasks.register<Copy>("copyVersionedDebugApk") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(layout.buildDirectory.dir("outputs/apk/versioned"))
    rename { "debug-$debugArtifactVersion.apk" }
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy(copyVersionedDebugApk)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
