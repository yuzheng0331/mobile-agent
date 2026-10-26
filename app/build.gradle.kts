import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.phoneagent.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.phoneagent.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Neo5 and current phones are arm64; other ABIs would only add native OCR libraries.
        ndk { abiFilters += "arm64-v8a" }
    }

    // Official task packs ship as assets straight from the repo's packs/ folder.
    sourceSets["main"].assets.srcDir("../packs")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// LiteRT 2.3.0 (debug only) carries Kotlin 2.4 metadata; this project's compiler is 2.0.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name == "compileDebugKotlin") compilerOptions.freeCompilerArgs.add("-Xskip-metadata-version-check")
}

dependencies {
    implementation(project(":core"))
    // Same version as core's compileOnly desktop runtime: core's OCR code is built against that API.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    // The GPU experiment (docs/ocr-experiments.md): the test page only, not shipped.
    // Without Play's AI-pack delivery (WorkManager, boot receivers): models are read from a file path.
    debugImplementation("com.google.ai.edge.litert:litert:2.3.0") { exclude(group = "com.google.android.play") }
    debugImplementation("com.google.ai.edge.litert:litert-gpu:2.3.0") { exclude(group = "com.google.android.play") }
}

// PP-OCRv6 small (Apache-2.0) from PaddlePaddle's Hugging Face repos, pinned to a revision and
// checked by SHA-256, so the 31 MB of weights stay out of git. dict.txt is committed next to them.
val ocrModels = mapOf(
    "det.onnx" to Triple("PP-OCRv6_small_det_onnx", "28fe5895c24fd108c19eb3e8479f4ab385fbfc62", "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e"),
    "rec.onnx" to Triple("PP-OCRv6_small_rec_onnx", "b8f84f0b80c529de40b4fbb3544b84fa7233a513", "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634"),
)

val fetchOcrModels by tasks.registering {
    val dir = layout.projectDirectory.dir("src/main/assets/ocr").asFile
    outputs.files(ocrModels.keys.map { File(dir, it) })
    doLast {
        fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        ocrModels.forEach { (name, model) ->
            val (repo, revision, hash) = model
            val file = File(dir, name)
            if (file.isFile && sha256(file) == hash) return@forEach
            URI("https://huggingface.co/PaddlePaddle/$repo/resolve/$revision/inference.onnx").toURL()
                .openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
            if (sha256(file) != hash) {
                file.delete()
                throw GradleException("$name from $repo@$revision failed its SHA-256 check")
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(fetchOcrModels) }
