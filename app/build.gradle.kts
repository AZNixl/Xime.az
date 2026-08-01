import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.nio.ByteBuffer
import java.nio.ByteOrder

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val onnxVersion = "1.28.0"
val onnxSrcUrl = "https://github.com/microsoft/onnxruntime/archive/refs/tags/v${onnxVersion}.tar.gz"

val downloadOnnx by tasks.registering {
    val cppDir = file("src/main/jni/onnxruntime")
    val jniLibsDir = file("src/main/jniLibs")
    val srcDir = file("$buildDir/onnxruntime-src")
    val buildOutDir = file("$buildDir/onnxruntime-build-android")
    val nnapiMarker = file("$buildDir/onnxruntime-nnapi-marker")

    outputs.dir(cppDir)
    outputs.dir(jniLibsDir)
    outputs.file(nnapiMarker)

    doLast {
        if (nnapiMarker.exists() && file("$cppDir/include/onnxruntime_c_api.h").exists()) {
            println("ONNX Runtime with NNAPI already built, skipping")
            return@doLast
        }

        // 1. Download & extract source tarball
        if (!srcDir.exists()) {
            println("Downloading ONNX Runtime v${onnxVersion} source...")
            val tarball = file("$buildDir/onnxruntime-${onnxVersion}.tar.gz")
            val curl = ProcessBuilder(
                "curl.exe", "--ssl-no-revoke", "-L", "-o", tarball.absolutePath, onnxSrcUrl
            ).directory(buildDir).redirectErrorStream(true).start()
            val curlOut = curl.inputStream.bufferedReader().readText()
            if (curl.waitFor() != 0) throw GradleException("Download failed: $curlOut")

            println("Extracting source...")
            val tar = ProcessBuilder("tar", "-xzf", tarball.absolutePath, "-C", buildDir.absolutePath)
                .directory(buildDir).redirectErrorStream(true).start()
            val tarOut = tar.inputStream.bufferedReader().readText()
            if (tar.waitFor() != 0) throw GradleException("Extract failed: $tarOut")

            File(buildDir, "onnxruntime-${onnxVersion}").renameTo(srcDir)
            tarball.delete()
            println("Source ready: $srcDir")
        }

        // 2. Locate NDK + SDK
        fun findDir(vararg envs: String, fallbackDir: String, subDir: String): String {
            for (env in envs) {
                System.getenv(env)?.let { if (File(it).exists()) return it }
            }
            val base = File(fallbackDir)
            if (base.exists()) {
                val candidates = File(base, subDir).listFiles()
                    ?.filter { it.isDirectory }?.sortedByDescending { it.name }
                if (!candidates.isNullOrEmpty()) return candidates.first().absolutePath
            }
            return ""
        }
        val ndkDir = findDir("ANDROID_NDK", "ANDROID_NDK_HOME",
            fallbackDir = "${System.getenv("LOCALAPPDATA")}/Android/Sdk", subDir = "ndk")
        val sdkDir = findDir("ANDROID_HOME", "ANDROID_SDK_ROOT",
            fallbackDir = "${System.getenv("LOCALAPPDATA")}/Android/Sdk", subDir = "")
        if (ndkDir.isEmpty() || sdkDir.isEmpty())
            throw GradleException("Cannot locate Android SDK/NDK. Set ANDROID_HOME and ANDROID_NDK.")
        println("SDK: $sdkDir")
        println("NDK: $ndkDir")

        // 3. Build arm64-v8a with NNAPI
        println("Building ONNX Runtime with NNAPI for arm64-v8a (30-60 min)...")
        buildOutDir.mkdirs()
        val buildProc = ProcessBuilder(
            "cmd.exe", "/c",
            "\"${srcDir.absoluteFile}\\build.bat\"",
            "--android", "--android_sdk_path", sdkDir, "--android_ndk_path", ndkDir,
            "--android_abi", "arm64-v8a", "--android_api", "27",
            "--cmake_generator", "Ninja", "--use_nnapi",
            "--config", "Release", "--build_dir", buildOutDir.absolutePath,
            "--parallel", "--skip_onnx_tests"
        ).directory(srcDir).redirectErrorStream(true).start()
        buildProc.inputStream.bufferedReader().use { r ->
            var line: String?; while (r.readLine().also { line = it } != null) { println(line) }
        }
        if (buildProc.waitFor() != 0) throw GradleException("Build failed")

        // 4. Locate built .so
        val allSos = fileTree(buildOutDir).matching { include("**/libonnxruntime.so") }.files
        if (allSos.isEmpty()) throw GradleException("No libonnxruntime.so in $buildOutDir")
        val builtSo = allSos.first()
        println("Built: ${builtSo.absolutePath} (${builtSo.length()} bytes)")

        // 5. Copy headers from source
        copy {
            from("${srcDir.absolutePath}/include/onnxruntime")
            into("${cppDir.absolutePath}/include")
        }

        // 6. Copy .so to jni link dir and jniLibs
        val arm64Lib = file("${cppDir.absolutePath}/lib/arm64-v8a")
        val arm64Jni = file("${jniLibsDir.absolutePath}/arm64-v8a")
        arm64Lib.mkdirs(); arm64Jni.mkdirs()
        builtSo.copyTo(File(arm64Lib, "libonnxruntime.so"), overwrite = true)
        builtSo.copyTo(File(arm64Jni, "libonnxruntime.so"), overwrite = true)

        // 7. Marker
        nnapiMarker.parentFile.mkdirs()
        nnapiMarker.writeText("NNAPI v${onnxVersion} built on ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}")
        println("ONNX Runtime with NNAPI deployed to arm64-v8a")
    }
}

val buildSherpaOnnx by tasks.registering {
    val jniLibsDir = file("src/main/jniLibs")
    val arm64Dir = file("$jniLibsDir/arm64-v8a")
    val sherpaOnnxSoArm64 = file("$arm64Dir/libsherpa-onnx-jni.so")

    outputs.file(sherpaOnnxSoArm64)

    dependsOn(downloadOnnx)

    doLast {
        if (sherpaOnnxSoArm64.exists()) {
            println("sherpa-onnx JNI library already exists, skipping")
            return@doLast
        }

        println("Downloading prebuilt sherpa-onnx JNI library...")

        arm64Dir.mkdirs()

        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.0/sherpa-onnx-v1.13.0-android.tar.bz2"
        val tarball = File(temporaryDir, "sherpa-onnx-android.tar.bz2")

        val curlCmd = mutableListOf("curl.exe", "--ssl-no-revoke", "-L", "-o", tarball.absolutePath, url)
        val proc = ProcessBuilder(curlCmd)
            .directory(temporaryDir)
            .redirectErrorStream(true)
            .start()
        val curlOut = proc.inputStream.bufferedReader().readText()
        if (proc.waitFor() != 0) {
            println("curl download failed: $curlOut")
            return@doLast
        }

        copy {
            from(tarTree(tarball)) {
                include("**/arm64-v8a/libsherpa-onnx-jni.so")
                eachFile { relativePath = RelativePath(true, name) }
                includeEmptyDirs = false
            }
            into(arm64Dir)
        }

        if (sherpaOnnxSoArm64.exists()) {
            println("sherpa-onnx JNI downloaded: ${sherpaOnnxSoArm64.length()} bytes")
        } else {
            println("WARNING: sherpa-onnx JNI download failed. ASR will use online mode only.")
        }
    }
}

val buildTrie by tasks.registering {
    val inputFile = file("src/main/assets/english.txt")
    val outputFile = file("src/main/assets/english_trie.bin")
    
    inputs.file(inputFile)
    outputs.file(outputFile)
    
    doLast {
        val words = inputFile.readLines()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        
        println("Loaded ${words.size} words from ${inputFile.name}")
        
        val nodes = mutableListOf<MutableMap<Char, Int>>()
        val nodeWords = mutableListOf<String?>()
        val nodeFreqs = mutableListOf<Int>()
        nodes.add(mutableMapOf())
        nodeWords.add(null)
        nodeFreqs.add(0)
        
        fun getOrCreateChild(parentIndex: Int, char: Char): Int {
            val existing = nodes[parentIndex][char]
            if (existing != null) return existing
            
            val newIndex = nodes.size
            nodes.add(mutableMapOf())
            nodeWords.add(null)
            nodeFreqs.add(0)
            nodes[parentIndex][char] = newIndex
            return newIndex
        }
        
        words.forEachIndexed { lineNum, word ->
            var current = 0
            for (char in word) {
                current = getOrCreateChild(current, char)
            }
            if (nodeWords[current] == null) {
                nodeWords[current] = word
                nodeFreqs[current] = lineNum + 1
            }
        }
        
        println("Built trie with ${nodes.size} nodes")
        
        val buffer = ByteBuffer.allocate(512 * 1024)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        buffer.put("TRIE".toByteArray())
        buffer.put(1)
        buffer.putInt(nodes.size)
        
        for (i in nodes.indices) {
            val children = nodes[i]
            buffer.put(children.size.toByte())
            for ((char, childIndex) in children) {
                buffer.put(char.code.toByte())
                buffer.putInt(childIndex)
            }
            
            val word = nodeWords[i]
            buffer.put(if (word != null) 1 else 0)
            if (word != null) {
                val bytes = word.toByteArray(Charsets.UTF_8)
                buffer.put(bytes.size.toByte())
                buffer.put(bytes)
                buffer.putInt(nodeFreqs[i])
            }
        }
        
        val data = ByteArray(buffer.position())
        buffer.flip()
        buffer.get(data)
        outputFile.writeBytes(data)
        
        println("Wrote ${data.size} bytes (${data.size / 1024}KB) to ${outputFile.name}")
    }
}

tasks.named("preBuild").configure {
    dependsOn(downloadOnnx)
    dependsOn(buildSherpaOnnx)
    dependsOn(buildTrie)
}

tasks.register("copyPluginsToAssets", Copy::class) {
    group = "plugin-dev"
    description = "Manually copy plugin APKs to assets for debugging"
    
    val pluginProjects = listOf(
        ":plugins:meme-bunny",
        ":plugins:kaomoji"
    )
    
    pluginProjects.forEach { pluginPath ->
        dependsOn(project(pluginPath).tasks.getByName("assembleDebug"))
        from(project(pluginPath).layout.buildDirectory.dir("outputs/apk/debug")) {
            include("*universal*.apk")
        }
    }
    
    into(layout.projectDirectory.dir("src/main/assets/plugins"))
    
    doFirst {
        layout.projectDirectory.dir("src/main/assets/plugins").asFile.mkdirs()
    }
}

tasks.register("clearPlugins", DefaultTask::class) {
    group = "plugin-dev"
    description = "Clear all plugin data from device (requires connected device with adb)"
    
    doLast {
        val packageName = "com.kingzcheung.xime"
        val pluginsDir = "/data/data/$packageName/files/plugins"
        
        println("=== Clearing Xime plugin data ===")
        
        val devicesCheck = executeCommand("adb devices")
        if (!devicesCheck.contains("device")) {
            println("ERROR: No connected device detected")
        } else {
            println("Clearing plugin directory...")
            executeCommand("adb shell rm -rf $pluginsDir")
            
            println("Clearing plugin config...")
            executeCommand("adb shell rm -rf /data/data/$packageName/shared_prefs/plugin_*.xml")
            executeCommand("adb shell rm -rf /data/data/$packageName/shared_prefs/plugins.xml")
            
            println("=== Done ===")
            println("Please restart Xime app to reload plugins")
        }
    }
}

tasks.register("uninstallApp", DefaultTask::class) {
    group = "plugin-dev"
    description = "Completely uninstall Xime app (clear all data)"
    
    doLast {
        val packageName = "com.kingzcheung.xime"
        
        println("=== Completely uninstalling Xime app ===")
        
        val devicesCheck = executeCommand("adb devices")
        if (!devicesCheck.contains("device")) {
            println("ERROR: No connected device detected")
        } else {
            println("Uninstalling $packageName...")
            val result = executeCommand("adb uninstall $packageName")
            println(result)
            
            println("=== Done ===")
            println("All app data cleared. Reinstall to start fresh.")
        }
    }
}

fun executeCommand(command: String): String {
    return try {
        val parts = command.split(" ")
        val process = ProcessBuilder(parts)
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().readText()
    } catch (e: Exception) {
        ""
    }
}



// 获取 Git 提交哈希
fun getGitHash(): String {
    return try {
        val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(rootDir)
            .start()
        process.inputStream.bufferedReader().readText().trim()
    } catch (e: Exception) {
        "unknown"
    }
}

// 获取构建时间
fun getBuildTime(): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
}

// 加载签名配置
val keystorePropertiesFile = rootProject.file("app/keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "com.kingzcheung.xime"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kingzcheung.xime"
        minSdk = 28
        targetSdk = 35
        versionCode = 20260724
        versionName = "2.6.0-beta2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // NDK 配置
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
        
        // 构建信息
        buildConfigField("String", "GIT_HASH", "\"${getGitHash()}\"")
        buildConfigField("String", "BUILD_TIME", "\"${getBuildTime()}\"")
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 只在本地有 keystore.properties 时才使用签名配置
            // GitHub Actions 使用自己的签名方式
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xunused")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }
    
    // NDK 构建配置
    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    
    // 打包配置
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    ndkVersion = "29.0.14206865"

    // 测试 classpath 包含 main assets，使 T9Decoder() 无参构造可加载 pinyin_lm.bin
    sourceSets {
        getByName("test") {
            resources.srcDirs("src/main/assets")
        }
    }
    lint {
        checkReleaseBuilds = false
        checkGeneratedSources = false
        abortOnError = false
        checkDependencies = true
    }
    
    // 分架构打包
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
}

android.applicationVariants.all {
    val appName = "Xime"
    outputs.all {
        val abi = filters.find { it.filterType.toString() == "ABI" }?.identifier ?: "universal"
        (this as BaseVariantOutputImpl).outputFileName = "$appName-$versionName-$abi.apk"
    }
}

// Nightly 构建通过 androidComponents API 覆盖 versionCode/versionName
androidComponents {
    onVariants { variant ->
        val vc = project.findProperty("versionCode")?.toString()?.toIntOrNull()
        val vn = project.findProperty("versionName")?.toString()
        if (vc != null && vn != null) {
            variant.outputs.forEach { output ->
                output.versionCode.set(vc)
                output.versionName.set(vn)
            }
        }
    }
}
dependencies {
    implementation(project(":plugin-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    
    // Kotlin stdlib - CRITICAL for plugin compatibility
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.10")
    implementation(libs.kotlinx.coroutines.core)
    
    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.activity.compose)
    
    // Material Icons
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    
    // Navigation
    implementation(libs.androidx.navigation.compose)
    
    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    
    // SavedState
    implementation(libs.androidx.savedstate)
    
    // Coil (Image Loading)
    implementation(libs.coil)
    
    // OkHttp for WebSocket and model download
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    // Apache Commons Compress for tar.bz2 extraction
    implementation("org.apache.commons:commons-compress:1.28.0")
    
    // Kaml for YAML parsing
    implementation(libs.kaml)

    // Autofill inline suggestions (API 30+)
    implementation(libs.androidx.autofill)

    // exp4j for calculator expression evaluation
    implementation(libs.exp4j)

    // ZXing for QR code generation
    implementation("com.google.zxing:core:3.5.4")

    // Ktor embedded server for wireless import
    implementation("io.ktor:ktor-server-core:3.5.1")
    implementation("io.ktor:ktor-server-cio:3.5.1")

    // Sora Code Editor for YAML viewing/editing
    implementation(platform("io.github.rosemoe:editor-bom:0.24.6"))
    implementation("io.github.rosemoe:editor")
    implementation("io.github.rosemoe:language-textmate")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    
    debugImplementation(libs.androidx.compose.ui.tooling)
    
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.concurrent:concurrent-futures:1.2.0")
}

// Align concurrent-futures version: espresso 3.7.0 requires 1.2.0
dependencies {
    constraints {
        implementation("androidx.concurrent:concurrent-futures:1.2.0") {
            because("test dependencies (espresso 3.7.0) require 1.2.0")
        }
    }
}
