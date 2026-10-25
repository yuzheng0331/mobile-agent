import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin: no Android dependency, so packs, finders and the runner are
// tested on the PC against recorded trees.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // OCR compiles against the desktop runtime and is tested with it; the app ships the
    // Android build, whose public API is the same. Keep both on the same version.
    compileOnly("com.microsoft.onnxruntime:onnxruntime:1.20.0")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // The OCR experiments hold ~30 full-screen screenshots in memory.
    maxHeapSize = "2g"
}
