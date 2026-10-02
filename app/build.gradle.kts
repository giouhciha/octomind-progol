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
        versionCode = 13
        versionName = "0.4.7"

        testInstrumentationRunner = "mx.octomind.progol.IntegrationInstrumentation"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
}
