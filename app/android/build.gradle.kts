allprojects {
    repositories {
        google()
        mavenCentral()
        // Magic Lane hosts the native maps-flutter-kotlin AAR on its own Maven
        // server, and magiclane_maps_flutter ships no bundled AAR (its android/libs
        // is empty), so this is the only source for it.
        //
        // The plugin does declare this repository, but only inside its own
        // build script — and Gradle resolves :app:releaseRuntimeClasspath against
        // :app's repository list, since repositories are not inherited across
        // project dependencies. Without it here the build configures fine and then
        // fails in execution at :app:mergeReleaseAssets with
        // "Could not find com.magiclane:maps-flutter-kotlin".
        //
        // Scoped with content{} so this third-party server is consulted only for
        // com.magiclane coordinates and can never shadow Maven Central or Google.
        maven {
            url = uri("https://developer.magiclane.com/packages/android")
            content { includeGroup("com.magiclane") }
        }
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}
subprojects {
    project.evaluationDependsOn(":app")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
