import java.time.Instant

plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android); alias(libs.plugins.compose); alias(libs.plugins.ksp) }

android { namespace="com.valcrono.virtualspace"; compileSdk=35
 defaultConfig {
     applicationId="com.valcrono.virtualspace"; minSdk=26; targetSdk=35; versionCode=1; versionName="0.1-phase1"; testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner"
     buildConfigField("String", "GIT_COMMIT", "\"${System.getenv("GITHUB_SHA") ?: runCatching { providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }.standardOutput.asText.get().trim() }.getOrDefault("unknown")}\"")
     buildConfigField("String", "BUILD_DATE", "\"${Instant.now()}\"")
 }
 buildFeatures { compose=true; buildConfig=true }
 packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies { implementation(project(":virtual-runtime-api")); implementation(project(":core")); implementation(project(":virtual-storage")); implementation(project(":virtual-package-manager")); implementation(project(":virtual-file-manager")); implementation(project(":virtual-process")); implementation(project(":compat-android15")); implementation(libs.androidx.core); implementation(libs.activity.compose); implementation(libs.androidx.fragment.ktx); implementation(libs.androidx.lifecycle.runtime.ktx); implementation(platform(libs.compose.bom)); implementation(libs.compose.ui); implementation(libs.compose.foundation); implementation(libs.compose.material3); implementation(libs.compose.material3.window); implementation(libs.compose.preview); implementation(libs.nav.compose); implementation(libs.coroutines.android); implementation(libs.room.runtime); implementation(libs.room.ktx); implementation(libs.datastore.preferences); implementation(libs.biometric); ksp(libs.room.compiler); testImplementation(libs.junit); androidTestImplementation(libs.junit); androidTestImplementation("androidx.test:core:1.6.1"); androidTestImplementation("androidx.test:runner:1.6.2") }
