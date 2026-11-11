pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FlashDrop"

include(":app")
include(":core:model", ":core:database", ":core:datastore", ":core:security", ":core:designsystem")
include(":domain", ":data")
include(":protocol")
include(":connectivity:api", ":connectivity:tls", ":connectivity:lan", ":connectivity:wifip2p")
include(":storage")
include(":service")
include(":feature:home", ":feature:send", ":feature:transfer", ":feature:history", ":feature:settings")
