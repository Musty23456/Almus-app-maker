package com.almus.appcreatorpro.ui.navigation

object Routes {
    const val SPLASH = "splash"
    const val WELCOME = "welcome"
    const val HOME = "home"
    const val PROJECTS = "projects"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val BUILDS = "builds"
    const val EDITOR = "editor/{projectId}"
    const val GITHUB = "github"
    const val REPOS = "repos/{projectId}"
    const val CLOUD_BUILD = "cloudbuild/{projectId}"
    const val BUILD_STATUS = "build/{buildId}"

    fun editor(projectId: String) = "editor/$projectId"
    fun repos(projectId: String) = "repos/$projectId"
    fun cloudBuild(projectId: String) = "cloudbuild/$projectId"
    fun buildStatus(buildId: String) = "build/$buildId"
}
