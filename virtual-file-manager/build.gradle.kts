plugins { alias(libs.plugins.kotlin.jvm) }
dependencies { implementation(project(":core")); implementation(project(":virtual-storage")); testImplementation(libs.junit) }
