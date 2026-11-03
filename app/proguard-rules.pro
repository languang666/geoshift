# Xposed entry point is loaded by name from assets/xposed_init.
-keep class app.geoshift.PortalHook { *; }
-keep class * extends de.robv.android.xposed.XC_MethodHook { *; }
-dontwarn de.robv.android.xposed.**
# Keep source file/line info readable in crash logs.
-keepattributes SourceFile,LineNumberTable
# The official map SDK uses JNI and reflective callbacks internally.
-keep class com.amap.api.** { *; }
-keep class com.autonavi.** { *; }
-dontwarn com.amap.api.**
-dontwarn com.autonavi.**
# Optional soft GNSS extension in the bundled location SDK; no location client is started.
-dontwarn com.amap.ams.gnss.GnssSoftLocator
