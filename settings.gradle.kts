pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "ValcronoVirtualSpace"
include(":virtual-runtime-api", ":app", ":core", ":virtual-package-manager", ":virtual-storage", ":virtual-process", ":virtual-file-manager", ":compat-android15", ":testapp-a", ":testapp-b")
