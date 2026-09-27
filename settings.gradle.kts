pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "folio"

// Module list: docs/architecture/02-modules.md#module-list
include(":app")
include(":core:common")
include(":core:model")
include(":core:format")
include(":core:storage")
include(":core:ink")
include(":core:text")
include(":core:render")
include(":core:pdf")
include(":core:designsystem")
include(":core:testing")
include(":feature:library")
include(":feature:editor")
include(":feature:settings")
include(":tools:icongen")
