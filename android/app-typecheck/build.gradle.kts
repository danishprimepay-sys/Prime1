import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Compiles the :app Kotlin sources against the Android framework jar published by Robolectric
// (API 35), for environments where the Android SDK/AGP cannot be installed. Never packaged.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(false)
    }
    sourceSets.named("main") {
        kotlin.setSrcDirs(listOf("../app/src/main/java"))
    }
}

dependencies {
    compileOnly("org.robolectric:android-all:15-robolectric-12650502")
    implementation(project(":core"))
}
