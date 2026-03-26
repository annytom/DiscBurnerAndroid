# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep audit log classes for debugging
-keep class com.enterprise.discburner.data.AuditEvent { *; }
-keep class com.enterprise.discburner.data.AuditCode { *; }
-keep class com.enterprise.discburner.data.BurnResult { *; }

# Keep USB related classes
-keep class com.enterprise.discburner.usb.** { *; }

# Keep Compose
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
