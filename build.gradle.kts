import org.gradle.internal.os.OperatingSystem
import proguard.gradle.ProGuardTask

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("com.guardsquare:proguard-gradle:7.4.2")
    }
}

plugins {
    id("java")
    id("application")
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "com.modelviewer"
version = "1.0.0"

// Detect LWJGL natives classifier for the current OS
val lwjglVersion = "3.3.3"
val lwjglNatives: String = when {
    OperatingSystem.current().isWindows -> "natives-windows"
    OperatingSystem.current().isMacOsX -> "natives-macos"
    else -> "natives-linux"
}

javafx {
    version = "21"
    modules = listOf("javafx.controls", "javafx.fxml")
}

application {
    mainClass.set("com.modelviewer.Main")
    applicationDefaultJvmArgs = listOf(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.nio=ALL-UNNAMED",
        "--add-exports", "javafx.graphics/com.sun.javafx.application=ALL-UNNAMED"
    )
}

repositories {
    mavenCentral()
}

dependencies {
    // ── LWJGL BOM controls all LWJGL module versions ────────────────────────
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))

    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-glfw")
    implementation("org.lwjgl:lwjgl-opengl")

    // Platform-specific native binaries (loaded at runtime by LWJGL)
    runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-opengl::$lwjglNatives")

    // ── Compression ─────────────────────────────────────────────────────────
    // OSRS cache archives are compressed with BZIP2, GZIP, or LZMA
    implementation("org.apache.commons:commons-compress:1.26.0")
    implementation("org.tukaani:xz:1.9")   // LZMA support for commons-compress

    // ── Logging ─────────────────────────────────────────────────────────────
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("org.slf4j:slf4j-simple:2.0.9")

    // ── Database ─────────────────────────────────────────────────────────────
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.named<JavaExec>("run") {
    jvmArgs(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.nio=ALL-UNNAMED",
        "--add-exports", "javafx.graphics/com.sun.javafx.application=ALL-UNNAMED"
    )
}

tasks.test {
    useJUnitPlatform()
}

// ── ProGuard obfuscation ─────────────────────────────────────────────────────
// Run with: ./gradlew obfuscate
// Output:   build/libs/Model-Viewer-obfuscated.jar
tasks.register<ProGuardTask>("obfuscate") {
    dependsOn(tasks.named("jar"))

    configuration("proguard-rules.pro")

    // Input: compiled jar
    injars(tasks.named("jar").get().outputs.files)

    // Runtime deps as library jars (referenced but not obfuscated)
    libraryjars(configurations.runtimeClasspath)

    // Java 9+ uses jmods instead of rt.jar
    libraryjars("${System.getProperty("java.home")}/jmods")

    outjars(layout.buildDirectory.file("libs/${rootProject.name}-obfuscated.jar"))
}
