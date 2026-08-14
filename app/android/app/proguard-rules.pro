# CairoDrive R8/ProGuard rules.
#
# Two things here are reached reflectively or over JNI, so R8 cannot see the
# references and would otherwise strip them:
#
#   1. The Magic Lane SDK bridges Dart <-> native through JNI and resolves
#      classes by name.
#   2. The AndroidX Car App Library instantiates the CarAppService and its
#      Session/Screen subclasses from the manifest and from the host.

# --- Magic Lane Maps SDK -----------------------------------------------------
-keep class com.magiclane.** { *; }
-keep interface com.magiclane.** { *; }
-dontwarn com.magiclane.**

# --- Flutter embedding -------------------------------------------------------
-keep class io.flutter.** { *; }
-keep class io.flutter.plugins.** { *; }
-dontwarn io.flutter.embedding.**

# --- AndroidX Car App (Android Auto) -----------------------------------------
-keep class androidx.car.app.** { *; }
-dontwarn androidx.car.app.**
# Our own car entry points are named in the manifest.
-keep class com.cairodrive.app.auto.** { *; }
-keep class com.cairodrive.app.MainActivity { *; }

# Anything the platform instantiates by name from the manifest.
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Keep annotations and generic signatures so reflective lookups still resolve.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
