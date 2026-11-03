package com.almus.appcreatorpro.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.almus.appcreatorpro.AlmusApplication
import com.almus.appcreatorpro.ui.about.AboutScreen
import com.almus.appcreatorpro.ui.ai.AiAssistantScreen
import com.almus.appcreatorpro.ui.ai.AiGeneratorScreen
import com.almus.appcreatorpro.ui.ai.AiSettingsScreen
import com.almus.appcreatorpro.ui.artifacts.ArtifactsScreen
import com.almus.appcreatorpro.ui.buildconfig.BuildConfigScreen
import com.almus.appcreatorpro.ui.builds.BuildHistoryScreen
import com.almus.appcreatorpro.ui.signing.SigningScreen
import com.almus.appcreatorpro.ui.builds.BuildStatusScreen
import com.almus.appcreatorpro.ui.cloudbuild.CloudBuildScreen
import com.almus.appcreatorpro.ui.dashboard.BuildDashboardScreen
import com.almus.appcreatorpro.ui.debug.DebugScreen
import com.almus.appcreatorpro.ui.github.GitHubConnectionScreen
import com.almus.appcreatorpro.ui.github.RepositorySelectorScreen
import com.almus.appcreatorpro.ui.github.RepositorySelectorViewModel
import com.almus.appcreatorpro.ui.editor.EditorScreen
import com.almus.appcreatorpro.ui.home.HomeScreen
import com.almus.appcreatorpro.ui.projects.ProjectsScreen
import com.almus.appcreatorpro.ui.settings.SettingsScreen
import com.almus.appcreatorpro.ui.splash.SplashScreen
import com.almus.appcreatorpro.ui.transfer.BackupsScreen
import com.almus.appcreatorpro.ui.transfer.ImportScreen
import com.almus.appcreatorpro.ui.welcome.WelcomeScreen

/** Root composable: owns the navigation graph. Bottom bar is shown by the three main screens. */
@Composable
fun AlmusApp(
    /** Phase 7: a build that a notification asked to open. Opened as soon as the start screens are done. */
    openBuildId: String? = null,
    onOpenBuildHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    val container = (LocalContext.current.applicationContext as AlmusApplication).container

    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    LaunchedEffect(openBuildId, currentRoute) {
        // Wait until the splash and welcome screens have finished, so they do not replace the build screen.
        if (openBuildId != null && currentRoute != null && currentRoute != Routes.SPLASH && currentRoute != Routes.WELCOME) {
            navController.navigate(Routes.buildStatus(openBuildId))
            onOpenBuildHandled()
        }
    }

    val navigateTopLevel: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(Routes.HOME) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    NavHost(navController = navController, startDestination = Routes.SPLASH) {
        composable(Routes.SPLASH) {
            SplashScreen(
                onFinished = {
                    val next = if (container.settingsRepository.isWelcomeCompleted()) Routes.HOME else Routes.WELCOME
                    navController.navigate(next) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.WELCOME) {
            WelcomeScreen(
                onGetStarted = {
                    container.settingsRepository.setWelcomeCompleted(true)
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onOpenProjects = { navigateTopLevel(Routes.PROJECTS) },
                onOpenProject = { id -> navController.navigate(Routes.editor(id)) },
                onOpenBuildHistory = { navController.navigate(Routes.DASHBOARD) },
                onOpenGitHub = { navController.navigate(Routes.GITHUB) },
                onOpenCloudBuild = { id -> navController.navigate(Routes.cloudBuild(id)) },
                bottomBar = { AlmusBottomBar(currentRoute = Routes.HOME, onNavigate = navigateTopLevel) },
            )
        }
        composable(Routes.PROJECTS) {
            ProjectsScreen(
                onOpenProject = { id -> navController.navigate(Routes.editor(id)) },
                onCloudBuild = { id -> navController.navigate(Routes.cloudBuild(id)) },
                bottomBar = { AlmusBottomBar(currentRoute = Routes.PROJECTS, onNavigate = navigateTopLevel) },
                onImport = { navController.navigate(Routes.IMPORT) },
                onBackups = { id -> navController.navigate(Routes.backups(id)) },
                onGenerateWithAi = { navController.navigate(Routes.AI_GENERATE) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
                onOpenGitHub = { navController.navigate(Routes.GITHUB) },
                onOpenSigning = { navController.navigate(Routes.SIGNING) },
                onOpenArtifacts = { navController.navigate(Routes.ARTIFACTS) },
                onOpenDashboard = { navController.navigate(Routes.DASHBOARD) },
                onOpenAiSettings = { navController.navigate(Routes.AI_SETTINGS) },
                onGenerateWithAi = { navController.navigate(Routes.AI_GENERATE) },
                bottomBar = { AlmusBottomBar(currentRoute = Routes.SETTINGS, onNavigate = navigateTopLevel) },
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.EDITOR,
            arguments = listOf(
                navArgument("projectId") { type = NavType.StringType },
                navArgument("path") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("line") { type = NavType.IntType; defaultValue = -1 },
                navArgument("col") { type = NavType.IntType; defaultValue = -1 },
            ),
        ) { entry ->
            val editorProjectId = entry.arguments?.getString("projectId").orEmpty()
            // Phase 8: the error dashboard opens a file at a line (-1 = not given).
            val jumpPath = entry.arguments?.getString("path")
            val jumpLine = entry.arguments?.getInt("line")?.takeIf { it > 0 }
            val jumpCol = entry.arguments?.getInt("col")?.takeIf { it > 0 }
            // Phase 5: remember when this project was opened (for "Recently opened").
            LaunchedEffect(editorProjectId) { container.recentProjects.markOpened(editorProjectId) }
            EditorScreen(
                projectId = editorProjectId,
                onBack = { navController.popBackStack() },
                initialPath = jumpPath,
                initialLine = jumpLine,
                initialColumn = jumpCol,
                onOpenDebug = { navController.navigate(Routes.debug(editorProjectId)) },
                onOpenAi = { navController.navigate(Routes.ai(editorProjectId)) },
            )
        }
        composable(
            route = Routes.AI,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
        ) { entry ->
            val aiProjectId = entry.arguments?.getString("projectId").orEmpty()
            AiAssistantScreen(
                projectId = aiProjectId,
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.AI_SETTINGS) },
                onOpenFile = { path -> navController.navigate(Routes.editorAt(aiProjectId, path, null, null)) { launchSingleTop = true } },
                onOpenDebug = { navController.navigate(Routes.debug(aiProjectId)) },
            )
        }
        composable(Routes.AI_SETTINGS) {
            AiSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.AI_GENERATE) {
            AiGeneratorScreen(
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.AI_SETTINGS) },
                onOpenProject = { id -> navController.navigate(Routes.editor(id)) { popUpTo(Routes.AI_GENERATE) { inclusive = true } } },
            )
        }
        composable(
            route = Routes.DEBUG,
            arguments = listOf(
                navArgument("projectId") { type = NavType.StringType },
                navArgument("buildId") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            val debugProjectId = entry.arguments?.getString("projectId").orEmpty()
            DebugScreen(
                projectId = debugProjectId,
                buildId = entry.arguments?.getString("buildId"),
                onBack = { navController.popBackStack() },
                onOpenFile = { path, line, col ->
                    // Open the file in the editor. The editor saved its files before opening this screen, and re-reads changed files when it resumes.
                    navController.navigate(Routes.editorAt(debugProjectId, path, line, col)) { launchSingleTop = true }
                },
            )
        }
        composable(Routes.IMPORT) {
            ImportScreen(
                onBack = { navController.popBackStack() },
                onOpenProject = { id ->
                    navController.navigate(Routes.editor(id)) { popUpTo(Routes.IMPORT) { inclusive = true } }
                },
                onOpenGitHub = { navController.navigate(Routes.GITHUB) },
            )
        }
        composable(
            route = Routes.BACKUPS,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
        ) { entry ->
            val raw = entry.arguments?.getString("projectId").orEmpty()
            BackupsScreen(
                projectId = raw.takeIf { it != Routes.ALL_BACKUPS },
                onBack = { navController.popBackStack() },
                onOpenProject = { id -> navController.navigate(Routes.editor(id)) },
            )
        }
        composable(Routes.DASHBOARD) {
            BuildDashboardScreen(
                onBack = { navController.popBackStack() },
                onOpenBuild = { id -> navController.navigate(Routes.buildStatus(id)) },
                onOpenFullHistory = { navController.navigate(Routes.BUILDS) },
            )
        }
        composable(Routes.BUILDS) {
            BuildHistoryScreen(
                onBack = { navController.popBackStack() },
                onOpenBuild = { id -> navController.navigate(Routes.buildStatus(id)) },
            )
        }
        composable(Routes.GITHUB) {
            GitHubConnectionScreen(
                onBack = { navController.popBackStack() },
                onBrowseRepositories = { navController.navigate(Routes.repos(RepositorySelectorViewModel.NO_PROJECT)) },
            )
        }
        composable(
            route = Routes.REPOS,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
        ) { entry ->
            RepositorySelectorScreen(
                projectId = entry.arguments?.getString("projectId").orEmpty(),
                onBack = { navController.popBackStack() },
                onOpenGitHub = { navController.navigate(Routes.GITHUB) },
            )
        }
        composable(
            route = Routes.CLOUD_BUILD,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
        ) { entry ->
            CloudBuildScreen(
                projectId = entry.arguments?.getString("projectId").orEmpty(),
                onBack = { navController.popBackStack() },
                onOpenGitHub = { navController.navigate(Routes.GITHUB) },
                onChooseRepository = { id -> navController.navigate(Routes.repos(id)) },
                onOpenBuild = { id -> navController.navigate(Routes.buildStatus(id)) },
                onOpenHistory = { navController.navigate(Routes.BUILDS) },
                onOpenBuildConfig = { id -> navController.navigate(Routes.buildConfig(id)) },
            )
        }
        composable(
            route = Routes.BUILD_CONFIG,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
        ) { entry ->
            BuildConfigScreen(
                projectId = entry.arguments?.getString("projectId").orEmpty(),
                onBack = { navController.popBackStack() },
                onOpenSigning = { navController.navigate(Routes.SIGNING) },
            )
        }
        composable(Routes.SIGNING) {
            SigningScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.ARTIFACTS) {
            ArtifactsScreen(
                onBack = { navController.popBackStack() },
                onOpenBuild = { id -> navController.navigate(Routes.buildStatus(id)) },
            )
        }
        composable(
            route = Routes.BUILD_STATUS,
            arguments = listOf(navArgument("buildId") { type = NavType.StringType }),
        ) { entry ->
            BuildStatusScreen(
                buildId = entry.arguments?.getString("buildId").orEmpty(),
                onBack = { navController.popBackStack() },
                onOpenHistory = { navController.navigate(Routes.BUILDS) },
                onOpenGitHub = { navController.navigate(Routes.GITHUB) },
                onOpenBuild = { id -> navController.navigate(Routes.buildStatus(id)) },
                onAnalyze = { projectId, buildId -> navController.navigate(Routes.debug(projectId, buildId)) },
            )
        }
    }
}
