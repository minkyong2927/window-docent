// Maven Central이 일부 파일(kotlin-compiler-embeddable)을 GitHub 다운로드로 넘기는데
// 그쪽이 504를 내는 경우가 있어 Google의 Maven Central 미러를 먼저 봄
val centralMirror = "https://maven-central.storage-download.googleapis.com/maven2/"

pluginManagement {
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven(centralMirror)
        mavenCentral()
    }
}
rootProject.name = "window-docent"
include(":app")
