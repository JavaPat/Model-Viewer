# ── Output options ───────────────────────────────────────────────────────────
-verbose
-printmapping build/proguard-mapping.txt   # save rename map for stack traces

# ── Keep entry point ─────────────────────────────────────────────────────────
-keep public class com.modelviewer.Main {
    public static void main(java.lang.String[]);
}

# ── Keep JavaFX Application subclass ─────────────────────────────────────────
-keep public class * extends javafx.application.Application

# ── Keep FXML controllers (JavaFX loads them by name via reflection) ──────────
# @FXML-annotated fields and methods must not be renamed
-keepclassmembers class * {
    @javafx.fxml.FXML *;
}

# If you reference controller classes from .fxml files by fully-qualified name,
# also keep the class names themselves:
# -keep class com.modelviewer.controller.** { *; }

# ── Keep LWJGL native bindings (JNI linkage) ─────────────────────────────────
-keep class org.lwjgl.** { *; }

# ── Keep JDBC driver registration (loaded via reflection by DriverManager) ───
-keep class org.sqlite.** { *; }
-keep class org.xerial.** { *; }

# ── Keep SLF4J service-loader bindings ───────────────────────────────────────
-keep class org.slf4j.** { *; }
-keep class org.slf4j.impl.** { *; }

# ── Suppress warnings for library classes we don't ship ──────────────────────
-dontwarn javafx.**
-dontwarn org.lwjgl.**
-dontwarn org.apache.commons.**
-dontwarn org.tukaani.**
-dontwarn org.sqlite.**
-dontwarn org.slf4j.**

# ── Obfuscation options ───────────────────────────────────────────────────────
-optimizationpasses 3
-allowaccessmodification
-repackageclasses ''          # flatten all packages into the root for extra obscurity
