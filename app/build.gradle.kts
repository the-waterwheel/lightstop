import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildAbiSplits = providers.gradleProperty("splitApks").map { it.toBooleanStrict() }.orElse(true).get()
val uiPreview = providers.gradleProperty("uiPreview").map { it.toBooleanStrict() }.orElse(false).get()

android {
    namespace = "com.lightmeter.rawmeter"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    ndkVersion = "27.0.12077973"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.lightmeter.rawmeter"
        minSdk = 28
        targetSdk = 36
        versionCode = 11
        versionName = "0.6.0"

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3")
            }
        }

        ndk {
            if (!buildAbiSplits) abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    // Default to smaller per-ABI downloads; retain a universal APK for distribution fallback.
    splits {
        abi {
            isEnable = buildAbiSplits
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo {
                include = false
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    if (uiPreview) {
        sourceSets.getByName("test").java.srcDir("src/uiPreview/java")
        testOptions.unitTests.isIncludeAndroidResources = true
        testOptions.unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            it.systemProperty("uiPreview.output", providers.gradleProperty("previewOutput").orElse("build/ui-preview").get())
            providers.gradleProperty("previewSdkDir").orNull?.let { directory ->
                it.systemProperty("robolectric.offline", "true")
                it.systemProperty("robolectric.dependency.dir", rootProject.file(directory).absolutePath)
            }
        }
    }
}

dependencies {
    implementation(files("libs/opencv-slim-4.12.0-r4-perf.aar"))
    testImplementation("junit:junit:4.13.2")
    if (uiPreview) testImplementation("org.robolectric:robolectric:4.14.1")
}

// The slim OpenCV runtime is a pinned local artifact. Fail the build if the bytes change so a
// swapped AAR cannot silently alter native behavior or licensing.
val verifyOpenCvAar = tasks.register("verifyOpenCvAar") {
    val aar = file("libs/opencv-slim-4.12.0-r4-perf.aar")
    val expected = "35e3b7df14f1b304a0eba7c0d6d15dd03900315e1d62d366df734243d7601662"
    inputs.file(aar)
    doLast {
        check(aar.isFile) { "Missing pinned OpenCV AAR: ${aar.absolutePath}" }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(aar.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(hash == expected) {
            "OpenCV AAR checksum mismatch: expected $expected but was $hash"
        }
    }
}

tasks.named("preBuild").configure { dependsOn(verifyOpenCvAar) }
