# libxposed: the module entry is named in META-INF/xposed/java_init.list; R8 rewrites that file if it renames the class.
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
# kotlinx.serialization keeps its generated serializers via its own consumer rules.
# Keep source file/line info readable in crash logs.
-keepattributes SourceFile,LineNumberTable
# AMap SDK: JNI and reflective callbacks. Its unused location part refers to optional classes.
-keep class com.amap.api.** { *; }
-keep class com.autonavi.** { *; }
-keep class com.loc.** { *; }
-dontwarn com.amap.api.**
-dontwarn com.autonavi.**
-dontwarn com.loc.**
-dontwarn com.amap.ams.**
