plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
    }
}

tasks.test {
    // Load tests (FdtpLoadTest) run only with -Dfdtp.load=true. The heap is capped so that a
    // multi-gigabyte transfer can only pass if the engine's memory use is bounded.
    val load = System.getProperty("fdtp.load") == "true"
    systemProperty("fdtp.load", load.toString())
    maxHeapSize = "128m"
    testLogging { showStandardStreams = load }
    if (load) outputs.upToDateWhen { false }
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.protobuf)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
