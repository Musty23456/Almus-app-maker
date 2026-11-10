package com.almus.appcreatorpro.ui.navigation

import com.almus.appcreatorpro.debug.RouteArgs

object Routes {
    const val SPLASH = "splash"
    const val WELCOME = "welcome"
    const val HOME = "home"
    const val PROJECTS = "projects"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val BUILDS = "builds"
    const val DASHBOARD = "dashboard"
    /** Optional query arguments (Phase 8): open this file and jump to this line/column. */
    const val EDITOR = "editor/{projectId}?path={path}&line={line}&col={col}"
    const val DEBUG = "debug/{projectId}?buildId={buildId}"
    const val GITHUB = "github"
    const val REPOS = "repos/{projectId}"
    const val CLOUD_BUILD = "cloudbuild/{projectId}"
    const val BUILD_STATUS = "build/{buildId}"
    const val IMPORT = "import"
    const val BUILD_CONFIG = "buildconfig/{projectId}"
    const val SIGNING = "signing"
    const val ARTIFACTS = "artifacts"
    const val BACKUPS = "backups/{projectId}"

    /** Phase 9: AI assistant for one project, AI settings (backend address) and "generate an app from a description". */
    const val AI = "ai/{projectId}"
    const val AI_SETTINGS = "aisettings"
    const val AI_GENERATE = "aigenerate"
    const val AGENT = "agent/{projectId}"
    const val PREVIEW = "preview/{projectId}"
    const val AGENT_NEW = "agentnew"

    /** Value of the BACKUPS route argument that means "all projects". */
    const val ALL_BACKUPS = "all"

    fun editor(projectId: String) = "editor/${RouteArgs.encode(projectId)}"

    /** Opens [path] in the editor with the caret at [line]:[column] (both optional). */
    fun editorAt(projectId: String, path: String, line: Int?, column: Int?): String =
        "editor/${RouteArgs.encode(projectId)}?path=${RouteArgs.encode(path)}" +
            (if (line != null) "&line=$line" else "") + (if (column != null) "&col=$column" else "")

    fun debug(projectId: String, buildId: String? = null): String =
        "debug/${RouteArgs.encode(projectId)}" + (if (buildId != null) "?buildId=${RouteArgs.encode(buildId)}" else "")
    fun ai(projectId: String) = "ai/${RouteArgs.encode(projectId)}"
    fun agent(projectId: String) = "agent/${RouteArgs.encode(projectId)}"
    fun preview(projectId: String) = "preview/${RouteArgs.encode(projectId)}"
    fun repos(projectId: String) = "repos/$projectId"
    fun cloudBuild(projectId: String) = "cloudbuild/$projectId"
    fun buildStatus(buildId: String) = "build/$buildId"
    fun buildConfig(projectId: String) = "buildconfig/$projectId"
    fun backups(projectId: String?) = "backups/${projectId ?: ALL_BACKUPS}"
}
