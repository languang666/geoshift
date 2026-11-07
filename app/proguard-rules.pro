# Xposed entry point is loaded by name from assets/xposed_init.
-keep class app.geoshift.PortalHook { *; }
-keep class * extends de.robv.android.xposed.XC_MethodHook { *; }
-dontwarn de.robv.android.xposed.**
# Keep source file/line info readable in crash logs.
-keepattributes SourceFile,LineNumberTable
