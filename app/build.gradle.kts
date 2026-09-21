import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lightmeter.rawmeter"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.lightmeter.rawmeter"
        minSdk = 28
        targetSdk = 36
        versionCode = 9
        versionName = "0.4.0"

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3")
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
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
}

dependencies {
    implementation(files("libs/opencv-slim-4.12.0-r2.aar"))
    testImplementation("junit:junit:4.13.2")
}

// The slim OpenCV runtime is a pinned local artifact. Fail the build if the bytes change so a
// swapped AAR cannot silently alter native behavior or licensing.
val verifyOpenCvAar = tasks.register("verifyOpenCvAar") {
    val aar = file("libs/opencv-slim-4.12.0-r2.aar")
    val expected = "321c84621fe818e35cc7b6401953039bc784e4fe4cfb9d35690b4dbedf4d57cd"
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
