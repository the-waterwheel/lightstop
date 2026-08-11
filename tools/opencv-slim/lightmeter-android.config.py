"""Pinned Android build matrix for the lightstop OpenCV runtime."""

import os


ANDROID_NATIVE_API_LEVEL = int(os.environ.get("ANDROID_NATIVE_API_LEVEL", 28))

cmake_common_vars = {
    "ANDROID_COMPILE_SDK_VERSION": os.environ.get("ANDROID_COMPILE_SDK_VERSION", 34),
    "ANDROID_TARGET_SDK_VERSION": os.environ.get("ANDROID_TARGET_SDK_VERSION", 34),
    "ANDROID_MIN_SDK_VERSION": os.environ.get(
        "ANDROID_MIN_SDK_VERSION", ANDROID_NATIVE_API_LEVEL
    ),
    "ANDROID_GRADLE_PLUGIN_VERSION": "8.2.2",
    "GRADLE_VERSION": "8.7",
    "KOTLIN_PLUGIN_VERSION": "1.9.22",
}

# Match app/build.gradle.kts. Keep arm64 first because it is the primary device ABI.
ABIs = [
    ABI(
        "3",
        "arm64-v8a",
        None,
        ndk_api_level=ANDROID_NATIVE_API_LEVEL,
        cmake_vars=cmake_common_vars,
    ),
    ABI(
        "2",
        "armeabi-v7a",
        None,
        ndk_api_level=ANDROID_NATIVE_API_LEVEL,
        cmake_vars=cmake_common_vars,
    ),
    ABI(
        "5",
        "x86_64",
        None,
        ndk_api_level=ANDROID_NATIVE_API_LEVEL,
        cmake_vars=cmake_common_vars,
    ),
]
