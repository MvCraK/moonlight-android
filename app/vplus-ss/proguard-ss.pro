# JNI entry points are named after this class.
-keep class com.limelight.ss.SsNative { native <methods>; }
-keep public class com.limelight.ss.SsPanel$ImportFragment { public <init>(); }
-dontwarn org.yaml.snakeyaml.**
