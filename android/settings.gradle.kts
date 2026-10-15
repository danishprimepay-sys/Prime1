import java.util.Properties

rootProject.name = "spamshield-android"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

// The Android SDK cannot always be installed (e.g. sandboxed dev boxes). Build the real
// :app only when an SDK is present; otherwise type-check the app sources against the
// android-all jar from Maven Central via :app-typecheck.
fun androidSdkAvailable(): Boolean {
    val candidates = mutableListOf(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"))
    val localProps = file("local.properties")
    if (localProps.isFile) {
        candidates += Properties().apply { localProps.inputStream().use { load(it) } }.getProperty("sdk.dir")
    }
    return candidates.filterNotNull().any { it.isNotBlank() && File(it).isDirectory }
}

include(":core")
if (androidSdkAvailable()) {
    include(":app")
} else {
    include(":app-typecheck")
}
