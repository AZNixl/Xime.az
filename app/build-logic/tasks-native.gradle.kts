import java.text.SimpleDateFormat
import java.util.Date

val onnxVersion = "1.28.0"
val onnxSrcUrl = "https://github.com/microsoft/onnxruntime/archive/refs/tags/v${onnxVersion}.tar.gz"

val isWindows = System.getProperty("os.name").lowercase().contains("win")

fun downloadCommand(vararg args: String): List<String> {
    val cmd = mutableListOf("curl")
    if (isWindows) cmd.add("--ssl-no-revoke")
    cmd.addAll(args)
    return cmd
}

fun downloadFile(url: String, target: File, workDir: File, desc: String): Boolean {
    println("Downloading $desc: $url")
    val cmd = downloadCommand("-#", "-L", "-o", target.absolutePath, url)
    return try {
        val proc = ProcessBuilder(cmd).directory(workDir).redirectErrorStream(true).start()
        val buf = ByteArray(4096)
        val tail = StringBuilder()
        proc.inputStream.use { input ->
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                val s = String(buf, 0, n)
                tail.append(s)
                if (tail.length > 2000) tail.delete(0, tail.length - 2000)
                print(s)
            }
        }
        println()
        if (proc.waitFor() != 0) {
            System.err.println("Download failed for $desc: ${tail.takeLast(500)}")
            false
        } else {
            println("Downloaded ${target.name} (${target.length()} bytes)")
            true
        }
    } catch (e: Exception) {
        System.err.println("Download error for $desc: ${e.message}")
        false
    }
}

val downloadOnnx by tasks.registering {
    val cppDir = file("src/main/jni/onnxruntime")
    val jniLibsDir = file("src/main/jniLibs")
    val srcDir = file("$buildDir/onnxruntime-src")
    val nnapiMarker = file("$buildDir/onnxruntime-nnapi-marker")

    outputs.dir(cppDir)
    outputs.dir(jniLibsDir)
    outputs.file(nnapiMarker)

    doLast {
        val allAbisPresent = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            .all { file("${jniLibsDir.absolutePath}/$it/libonnxruntime.so").exists() }
        if (allAbisPresent && file("$cppDir/include/onnxruntime_c_api.h").exists()) {
            println("ONNX Runtime with NNAPI already built for all ABIs, skipping")
            return@doLast
        }

        // 1. Download & extract source tarball
        if (!srcDir.exists()) {
            val tarball = file("$buildDir/onnxruntime-${onnxVersion}.tar.gz")
            if (!downloadFile(onnxSrcUrl, tarball, buildDir, "ONNX Runtime v${onnxVersion} source"))
                throw GradleException("Failed to download ONNX Runtime source")

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

        // 3. Build each ABI with NNAPI (CPU + NNAPI execution providers)
        val onnxAbis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        for (abi in onnxAbis) {
            val abiBuildDir = file("$buildDir/onnxruntime-build-android-$abi")
            println("Building ONNX Runtime with NNAPI for $abi (CPU + NNAPI, 30-60 min)...")
            abiBuildDir.mkdirs()
            val onnxBuild = mutableListOf<String>()
            if (isWindows) {
                onnxBuild.addAll(listOf("cmd.exe", "/c", "\"${srcDir.absoluteFile}\\build.bat\""))
            } else {
                onnxBuild.addAll(listOf("bash", "${srcDir.absoluteFile}/build.sh"))
            }
            onnxBuild.addAll(listOf(
                "--android", "--android_sdk_path", sdkDir, "--android_ndk_path", ndkDir,
                "--android_abi", abi, "--android_api", "27",
                "--cmake_generator", "Ninja", "--use_nnapi",
                "--config", "Release", "--build_dir", abiBuildDir.absolutePath,
                "--parallel", "--skip_onnx_tests"
            ))
            val buildProc = ProcessBuilder(onnxBuild)
                .directory(srcDir).redirectErrorStream(true).start()
            buildProc.inputStream.bufferedReader().use { r ->
                var line: String?; while (r.readLine().also { line = it } != null) { println(line) }
            }
            if (buildProc.waitFor() != 0) throw GradleException("ONNX Runtime build failed for $abi")

            // 4. Locate built .so for this ABI
            val sos = fileTree(abiBuildDir).matching { include("**/libonnxruntime.so") }.files
            if (sos.isEmpty()) throw GradleException("No libonnxruntime.so in $abiBuildDir")
            val builtSo = sos.first()
            println("Built $abi: ${builtSo.absolutePath} (${builtSo.length()} bytes)")

            // 5. Copy .so to jni link dir and jniLibs
            val abiLib = file("${cppDir.absolutePath}/lib/$abi")
            val abiJni = file("${jniLibsDir.absolutePath}/$abi")
            abiLib.mkdirs(); abiJni.mkdirs()
            builtSo.copyTo(File(abiLib, "libonnxruntime.so"), overwrite = true)
            builtSo.copyTo(File(abiJni, "libonnxruntime.so"), overwrite = true)
        }

        // 6. Copy headers from source (ABI-independent)
        copy {
            from("${srcDir.absolutePath}/include/onnxruntime")
            into("${cppDir.absolutePath}/include")
        }

        // 7. Marker
        nnapiMarker.parentFile.mkdirs()
        nnapiMarker.writeText("NNAPI v${onnxVersion} built on ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}")
        println("ONNX Runtime with NNAPI deployed to: ${onnxAbis.joinToString()}")
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

        arm64Dir.mkdirs()

        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.0/sherpa-onnx-v1.13.0-android.tar.bz2"
        val tarball = File(temporaryDir, "sherpa-onnx-android.tar.bz2")

        if (!downloadFile(url, tarball, temporaryDir, "prebuilt sherpa-onnx JNI library")) {
            println("WARNING: sherpa-onnx JNI download failed. ASR will use online mode only.")
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

        val buffer = java.nio.ByteBuffer.allocate(512 * 1024)
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)

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
