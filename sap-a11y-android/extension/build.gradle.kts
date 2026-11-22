// Canonical build of the extension, for the Morphe patches Gradle plugin
// (./gradlew buildAndroid). tools/build_extension.sh produces the same dex with
// only the Android SDK, for environments without access to the Morphe registry.
extension {
    name = "extensions/sapa11y.mpe"
}

android {
    namespace = "app.sapa11y.extension"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
