pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // karoo-ext, copiado de GitHub Packages (ver libs/README.md)
        maven {
            url = uri(settingsDir.resolve("libs/maven"))
            content { includeGroup("io.hammerhead") }
        }
    }
}

rootProject.name = "BiciCAM"
include("app")
