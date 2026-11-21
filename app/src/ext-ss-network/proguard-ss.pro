# EXTENSION DEVELOPMENT [EXT-SS-NETWORK] [ADDED]
# JNI entry points are named after this class.
-keep class com.limelight.extensions.ss.SsNative { native <methods>; }
-keep public class com.limelight.extensions.ss.SsImportFragment { public <init>(); }
-dontwarn org.yaml.snakeyaml.**
