-keep class com.lightmeter.rawmeter.RawMeterBridge { *; }

# OpenCV's Java wrappers cross the JNI boundary. Keep their class and member
# names stable so R8 cannot break native method lookup in release builds.
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
