# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keep class us.fireshare.tweet.HproseInstance { *; }
-keep class us.fireshare.tweet.datamodel.HproseService { *; }
-keep class us.fireshare.tweet.datamodel.ScorePair { *; }
-keep class us.fireshare.tweet.widget.Gadget { *; }
-keep class us.fireshare.tweet.viewmodel.** { *; }
-keep class hprose.client.** { *; }
-keep class us.fireshare.tweet.** { *; }

# Keep Timber logging for release builds
-keep class timber.log.** { *; }
-dontwarn timber.log.**

# Keep android.util.Log calls (don't remove them with ProGuard)
# DO NOT uncomment the following - it would remove all log calls
# -assumenosideeffects class android.util.Log {
#     public static *** v(...);
#     public static *** d(...);
#     public static *** i(...);
#     public static *** w(...);
#     public static *** e(...);
# }
