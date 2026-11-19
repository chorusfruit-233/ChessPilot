plugins {
    id("com.android.library")
}

android {
    namespace = "io.github.a1175815821.chesspilot"
    compileSdk = 37

    defaultConfig {
        minSdk = 27
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    // Supplied by the framework in the target process; never package the API.
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("org.luckypray:dexkit:2.3.0")
    testImplementation("junit:junit:4.13.2")
}

// Package the profiles from the audited APKs with the module code. The target
// process may not have access to the module's Android resources or assets.
val generateRemapTable by tasks.registering {
    val input = file("src/main/assets/dex-profiles.json")
    val outDir = layout.buildDirectory.dir("generated/remap/io/github/a1175815821/chesspilot")
    inputs.file(input)
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile.apply { mkdirs() }
        val json = input.readText(Charsets.UTF_8)
        // Escape for a Kotlin triple-quoted string: backslashes first, then dollar signs.
        val dollar = '$'
        val escaped = json.replace("\\", "\\\\").replace("$", dollar + "{'$'}")
        val tq = "\"\"\""
        val body = "package io.github.a1175815821.chesspilot\n\n" +
            "/** Auto-generated from module/src/main/assets/dex-profiles.json. */\n" +
            "internal object RemapTable {\n" +
            "    const val JSON = " + tq + escaped + tq + "\n" +
            "}\n"
        File(dir, "RemapTable.kt").writeText(body, Charsets.UTF_8)
    }
}

android {
    sourceSets {
        getByName("main").kotlin.directories.add(layout.buildDirectory.dir("generated/remap").get().asFile.absolutePath)
    }
}

tasks.named("preBuild") { dependsOn(generateRemapTable) }
