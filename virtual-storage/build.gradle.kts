plugins { alias(libs.plugins.kotlin.jvm) }
dependencies { implementation(project(":core")); implementation(libs.coroutines.core); testImplementation(libs.junit) }
