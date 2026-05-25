# MotoMesh ProGuard rules
# Opus codec (Concentus) — keep all native/JNI references
-keep class org.concentus.** { *; }

# Coroutines — keep flow/suspending internals
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep Android lifecycle components
-keep class * extends androidx.lifecycle.ViewModel { *; }

# Keep data classes used for serialization
-keep class com.motomesh.mesh.NodeRecord { *; }