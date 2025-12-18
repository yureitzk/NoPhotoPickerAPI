# Keep your main Xposed module class
-keep class com.yureitzk.nophotopickerapi.MainHook { *; }

# Keep all module classes (prevents stripping of hook targets)
-keep class com.yureitzk.nophotopickerapi.** { *; }

-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.jvm.internal.** { *; }
