"""Pinned Android build matrix for the lightstop OpenCV runtime."""

import os


ANDROID_NATIVE_API_LEVEL = int(os.environ.get("ANDROID_NATIVE_API_LEVEL", 28))

cmake_common_vars = {
    "ANDROID_COMPILE_SDK_VERSION": os.environ.get("ANDROID_COMPILE_SDK_VERSION", 34),
    "ANDROID_TARGET_SDK_VERSION": os.environ.get("ANDROID_TARGET_SDK_VERSION", 34),
    "ANDROID_MIN_SDK_VERSION": os.environ.get(
        "ANDROID_MIN_SDK_VERSION", ANDROID_NATIVE_API_LEVEL
    ),
    "ANDROID_GRADLE_PLUGIN_VERSION": "8.10.0",
    "GRADLE_VERSION": "8.13",
    "KOTLIN_PLUGIN_VERSION": "2.1.20",
    "ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES": "ON",
    "CMAKE_SHARED_LINKER_FLAGS": (
        "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
    ),
    # The application ships the Java/native runtime only; OpenCV's C++ test binaries are not
    # part of the AAR and needlessly multiply clean-build time for every ABI.
    "BUILD_TESTS": "OFF",
    "INSTALL_TESTS": "OFF",
    "WITH_TBB": "OFF",
    "BUILD_TBB": "OFF",
    "WITH_IPP": "OFF",
    "WITH_KLEIDICV": "OFF",
    "WITH_ITT": "OFF",
    # Keep imgcodecs' Java ABI and the PNG/JPEG defaults, but omit large optional file formats
    # that the camera-only application never decodes or encodes.
    "WITH_OPENJPEG": "OFF",
    "WITH_TIFF": "OFF",
    "WITH_WEBP": "OFF",
    "WITH_OPENEXR": "OFF",
    "WITH_AVIF": "OFF",
    "WITH_JASPER": "OFF",
    "WITH_ADE": "OFF",
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
