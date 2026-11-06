-keep class com.lightmeter.rawmeter.RawMeterBridge { *; }

# Production APKs do not emit application diagnostics to logcat.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}

# OpenCV's Java wrappers cross the JNI boundary. Keep their class and member
# names stable so R8 cannot break native method lookup in release builds.
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
