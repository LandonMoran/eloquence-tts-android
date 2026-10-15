# Eloquence TTS for Android — no shrinking in the legacy build, so we keep the
# shipped classes intact. Crashlytics needs the Firebase + app classes kept.
-keepattributes LineNumberTable,SourceFile
-keepparameternames

# Firebase / Crashlytics run fine without extra rules at default keep; this
# guard keeps our JNI native entry points from ever being renamed/removed,
# matching the undeobfuscatable hand-rolled build.
-keep class com.xw.vvtts.core.** { *; }
-keep class com.xw.vvtts.engine.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}