import com.android.build.gradle.internal.api.BaseVariantOutputImpl

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kingzcheung.xime.plugin.emoji"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kingzcheung.xime.plugin.emoji"
        minSdk = 28
        targetSdk = 35
        versionCode = 20260804
        versionName = "2.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    
    buildFeatures {
        compose = true
    }
    
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }
}

android.applicationVariants.all {
    val pluginName = "meme-bunny"
    outputs.all {
        (this as BaseVariantOutputImpl).outputFileName = "$pluginName-$versionName.xipk"
    }
}

dependencies {
    constraints {
        implementation("org.jetbrains:annotations:23.0.0")
    }
    compileOnly(project(":plugin-core"))
    // stdlib 由 plugin-core 的 api(kotlin("stdlib")) 传递到编译类路径（compileOnly 不打包），跟随宿主；
    // coil 自身传递的 stdlib 排除，运行时 parent-first 解析到宿主那份。
    implementation("io.coil-kt:coil-compose:2.7.0") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }

    testImplementation(project(":plugin-core"))
    testImplementation("junit:junit:4.13.2")
}