plugins {
    id ("com.android.application")
}

apply { from ("../../common.gradle") }

// The manager links Oboe as a shared library, but AGP only copies prefab binaries into an APK
// when the consuming module runs CMake itself. This app has no native build, so liboboe.so would
// never be packaged and the manager would fail to load at runtime. Unpack it from the AAR instead.
val oboeJniLibsDir = File(buildDir, "generated/oboeJniLibs")

val unpackOboe = tasks.register<Copy>("unpackOboe") {
    from (zipTree(configurations.detachedConfiguration(
        dependencies.create("com.google.oboe:oboe:${libs.versions.oboe.get()}")).singleFile))
    include ("prefab/modules/oboe/libs/android.*/liboboe.so")
    eachFile {
        // prefab/modules/oboe/libs/android.<abi>/liboboe.so
        val abi = relativePath.segments[4].removePrefix("android.")
        relativePath = RelativePath(true, abi, file.name)
    }
    includeEmptyDirs = false
    into (oboeJniLibsDir)
}

android {
    namespace = "org.androidaudioplugin.minidahost"
    defaultConfig {
        applicationId = "org.androidaudioplugin.minidahost"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation (project(":androidaudioplugin"))
    implementation (project(":androidaudioplugin-manager"))
    implementation (libs.androidx.core.ktx)
    implementation (libs.androidx.activity)
    implementation (libs.kotlin.stdlib.jdk8)
    implementation (libs.coroutines.core)
    implementation (libs.coroutines.android)
    implementation (libs.ktmidi)

    runtimeOnly (libs.libcxx.provider)

    testImplementation (libs.junit)
}

androidComponents {
    onVariants { variant ->
        // The supported way to attach a generated directory: it carries the task dependency, so a
        // clean build unpacks Oboe instead of silently shipping an APK that cannot load.
        variant.sources.jniLibs?.addGeneratedSourceDirectory(unpackOboe, Copy::getDestinationDirectory)
    }
}

gradle.projectsEvaluated {
    rootProject.project("androidaudioplugin").tasks.findByName("mergeDebugNativeLibs")?.let {
        tasks["mergeDebugNativeLibs"].dependsOn(it)
    }
    rootProject.project("androidaudioplugin").tasks.findByName("mergeReleaseNativeLibs")?.let {
        tasks["mergeReleaseNativeLibs"].dependsOn(it)
    }
}
