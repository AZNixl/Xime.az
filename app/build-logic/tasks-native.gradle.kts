import java.text.SimpleDateFormat
import java.util.Date

// 使用官方 Maven 预编译 onnxruntime-android AAR（内置 CPU + NNAPI EP），
// 不再从源码自行编译（自行编译需 30-60 分钟且依赖网络/NDK 稳定性）。
val onnxVersion = "1.28.0"
val onnxAarUrl = "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/${onnxVersion}/onnxruntime-android-${onnxVersion}.aar"
// 头文件不在 AAR 内，需从对应版本源码 raw 取（仅需 C API + provider factory 头）
val onnxHeadersBase = "https://raw.githubusercontent.com/microsoft/onnxruntime/v${onnxVersion}/include/onnxruntime"

// GitHub 加速镜像（按顺序尝试；不可用时请替换/删除）
val ghMirrors = listOf(
    "https://ghfast.top",
    "https://gh-proxy.com",
    "https://ghproxy.net",
)

val isWindows = System.getProperty("os.name").lowercase().contains("win")

// 本机 curl 直连官方 GitHub 源常因 schannel TLS 握手失败（SSL error 35），
// 而 PowerShell 的 Invoke-WebRequest（.NET TLS）与浏览器一致、更可靠。
// Windows 下优先用 IWR；Linux/macOS 用 curl。
fun downloadCommand(vararg args: String): List<String> {
    if (isWindows) {
        // args 形如: -L --retry 5 ... -o <target> <url>
        val url = args.last()
        val outIdx = args.indexOf("-o")
        val out = if (outIdx >= 0) args[outIdx + 1] else null
        val script = StringBuilder()
        script.append("[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12;")
        if (out != null) {
            script.append("Invoke-WebRequest -Uri '").append(url).append("' -OutFile '").append(out)
                .append("' -UseBasicParsing -TimeoutSec 600;")
        } else {
            script.append("Invoke-WebRequest -Uri '").append(url).append("' -UseBasicParsing -TimeoutSec 600 | Out-Null;")
        }
        return listOf("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script.toString())
    }
    return mutableListOf<String>().apply { add("curl"); addAll(args) }
}

// 单个 URL 下载，支持断点续传与自动重试
fun downloadFile(url: String, target: File, workDir: File, desc: String): Boolean {
    println("Downloading $desc: $url")
    val cmd = downloadCommand(
        "-L", "--retry", "5", "--retry-delay", "3", "--retry-all-errors",
        "-C", "-", // 断点续传
        "-o", target.absolutePath, url
    )
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
            }
        }
        val code = proc.waitFor()
        if (code != 0) {
            System.err.println("Download failed for $desc (exit $code): ${tail.takeLast(500)}")
            return false
        }
        if (target.exists() && target.length() == 0L) { target.delete(); return false }
        println("Downloaded ${target.name} (${target.length()} bytes)")
        true
    } catch (e: Exception) {
        System.err.println("Download error for $desc: ${e.message}")
        false
    }
}

// 按顺序尝试多个 URL（镜像优先，最后官方源），成功即停止，失败自动切换到下一个
fun downloadWithMirrors(urls: List<String>, target: File, workDir: File, desc: String): Boolean {
    for (url in urls) {
        // 已完整下载则跳过
        if (target.exists() && target.length() > 0) return true
        if (downloadFile(url, target, workDir, desc)) return true
        // 失败后清空残留半成品，避免续传污染下一源
        target.delete()
    }
    return false
}

fun githubUrls(url: String): List<String> = buildList {
    ghMirrors.forEach { add("${it.trimEnd('/')}/$url") }
    add(url) // 官方源兜底
}

val downloadOnnx by tasks.registering {
    val cppDir = file("src/main/jni/onnxruntime")
    val jniLibsDir = file("src/main/jniLibs")
    val nnapiMarker = file("$buildDir/onnxruntime-nnapi-marker")

    outputs.dir(cppDir)
    outputs.dir(jniLibsDir)
    outputs.file(nnapiMarker)

    doLast {
        val abis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

        // 已全部就绪则跳过
        val allSoPresent = abis.all {
            file("${jniLibsDir.absolutePath}/$it/libonnxruntime.so").exists() &&
            file("${cppDir.absolutePath}/lib/$it/libonnxruntime.so").exists()
        }
        val headersPresent = file("$cppDir/include/onnxruntime_c_api.h").exists()
        if (allSoPresent && headersPresent) {
            println("ONNX Runtime (official AAR) already deployed for all ABIs, skipping")
            return@doLast
        }

        // 1. 下载官方 AAR（含 4 ABI 的 libonnxruntime.so，内置 CPU + NNAPI EP）
        val aar = File(buildDir, "onnxruntime-android-${onnxVersion}.aar")
        if (!downloadWithMirrors(listOf(onnxAarUrl), aar, buildDir, "onnxruntime-android AAR")) {
            throw GradleException("Failed to download ONNX Runtime Android AAR: $onnxAarUrl")
        }
        println("AAR downloaded: ${aar.length()} bytes")

        // 2. 解压 AAR（zip），提取 jni/<abi>/libonnxruntime.so
        val aarDir = File(buildDir, "onnxruntime-aar")
        try {
            copy {
                from(zipTree(aar))
                into(aarDir)
            }
        } catch (e: Exception) {
            throw GradleException("Failed to extract AAR: ${e.message}")
        }

        for (abi in abis) {
            val src = File(aarDir, "jni/$abi/libonnxruntime.so")
            if (!src.exists()) throw GradleException("AAR missing jni/$abi/libonnxruntime.so")
            val abiLib = File(cppDir, "lib/$abi")
            val abiJni = File(jniLibsDir, abi)
            abiLib.mkdirs(); abiJni.mkdirs()
            src.copyTo(File(abiLib, "libonnxruntime.so"), overwrite = true)
            src.copyTo(File(abiJni, "libonnxruntime.so"), overwrite = true)
            println("Deployed libonnxruntime.so [$abi] (${src.length()} bytes)")
        }

        // 下载 v1.28 core/session/ 目录下全部头文件（c_api.h 依赖 ep_c_api.h、
        // error_code.h 等，需完整覆盖以通过编译），统一提升到 include 顶层供 #include 直接命中。
        val dstHeaders = File(cppDir, "include")
        dstHeaders.mkdirs()
        val headersToFetch = listOf(
            "core/session/environment.h",
            "core/session/experimental_onnxruntime_cxx_api.h",
            "core/session/experimental_onnxruntime_cxx_inline.h",
            "core/session/onnxruntime_c_api.h",
            "core/session/onnxruntime_cxx_api.h",
            "core/session/onnxruntime_cxx_inline.h",
            "core/session/onnxruntime_env_config_keys.h",
            "core/session/onnxruntime_ep_c_api.h",
            "core/session/onnxruntime_ep_device_ep_metadata_keys.h",
            "core/session/onnxruntime_error_code.h",
            "core/session/onnxruntime_experimental_c_api.h",
            "core/session/onnxruntime_experimental_c_api.inc",
            "core/session/onnxruntime_experimental_cxx_api.h",
            "core/session/onnxruntime_float16.h",
            "core/session/onnxruntime_lite_custom_op.h",
            "core/session/onnxruntime_run_options_config_keys.h",
            "core/session/onnxruntime_session_options_config_keys.h",
        )
        // 旧源码编译残留的 v1.28 头文件会与官方 v1.27 .so 不匹配（ORT_API_VERSION 不同），
        // 导致 GetApi(28) 失败。部署前清理 include 目录，确保头文件与 AAR 版本一致。
        dstHeaders.listFiles()?.forEach { old ->
            if (old.isFile) old.delete()
        }
        for (rel in headersToFetch) {
            val target = java.io.File(dstHeaders, rel.substringAfterLast("/"))
            val url = "$onnxHeadersBase/$rel"
            if (downloadWithMirrors(githubUrls(url), target, buildDir, "onnxruntime header $rel")) {
                println("Header ok: ${target.name}")
            } else {
                System.err.println("WARNING: failed to fetch header $rel")
            }
        }

        // 4. Marker
        nnapiMarker.parentFile.mkdirs()
        nnapiMarker.writeText("ONNX Runtime v${onnxVersion} (official AAR, CPU+NNAPI) deployed on ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}")
        println("ONNX Runtime deployed to: ${abis.joinToString()}")
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

        if (!downloadWithMirrors(githubUrls(url), tarball, temporaryDir, "prebuilt sherpa-onnx JNI library")) {
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
