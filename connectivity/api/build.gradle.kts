plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
    api(project(":core:model"))
    api(project(":protocol"))
    api(libs.kotlinx.coroutines.core)
}
