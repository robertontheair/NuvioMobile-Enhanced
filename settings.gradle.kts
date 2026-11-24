rootProject.name = "Nuvio"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":composeApp")
// Total Eclipse: build the Android modules only where an Android SDK is installed, so an
// iOS-only Mac (no SDK) can still run the iOS build, which configures every module.
val androidSdkInstalled = run {
    val props = java.util.Properties()
    val local = file("local.properties")
    if (local.exists()) local.inputStream().use { props.load(it) }
    val sdk = props.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    sdk != null && file(sdk).resolve("platforms").isDirectory
}
if (androidSdkInstalled) {
    include(":androidApp")
    include(":baselineprofile")
}
