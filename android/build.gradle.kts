// Plugins live on the root buildscript classpath so that the Kotlin and Android Gradle
// plugins share one classloader across subprojects. AGP is only resolved when :app is part
// of the build, because it is hosted on Google's Maven repository.
buildscript {
    val kotlinVersion = "2.0.21"
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
        classpath("org.jetbrains.kotlin:kotlin-serialization:$kotlinVersion")
        if (findProject(":app") != null) {
            classpath("com.android.tools.build:gradle:8.7.3")
        }
    }
}
