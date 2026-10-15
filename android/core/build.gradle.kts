import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val sharedDir = rootProject.projectDir.resolve("../shared").normalize()

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test-junit"))
}

// Bundle the single shared copy of the rules and model as classpath resources under
// spamshield/, so they load identically from the JVM and from inside an APK.
tasks.processResources {
    from(sharedDir) {
        include("rules.json", "model/sms_classifier.json")
        into("spamshield")
    }
}

tasks.test {
    val vectors = sharedDir.resolve("test-vectors")
    inputs.dir(vectors).withPropertyName("testVectors")
    systemProperty("spamshield.vectors", vectors.absolutePath)
    testLogging {
        events("failed")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
