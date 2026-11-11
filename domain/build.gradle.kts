plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    api(libs.javax.inject)
}
