pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Linphone SDK (liblinphone) is not published to Maven Central; it
        // ships only from Belledonne's own Maven repository. Scoped to the
        // org.linphone* groups so nothing else is ever fetched from here.
        maven {
            url = uri("https://download.linphone.org/maven_repository")
            content {
                includeGroupByRegex("org\\.linphone.*")
            }
        }
    }
}

rootProject.name = "Phomo"
include(":app")
