package com.almus.appcreatorpro.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.almus.appcreatorpro.AlmusApplication
import com.almus.appcreatorpro.ui.about.AboutScreen
import com.almus.appcreatorpro.ui.builds.BuildHistoryScreen
import com.almus.appcreatorpro.ui.builds.BuildStatusScreen
import com.almus.appcreatorpro.ui.cloudbuild.CloudBuildScreen
import com.almus.appcreatorpro.ui.github.GitHubConnectionScreen
import com.almus.appcreatorpro.ui.github.RepositorySelectorScreen
import com.almus.appcreatorpro.ui.github.RepositorySelectorViewModel
import com.almus.appcreatorpro.ui.editor.EditorScreen
import com.almus.appcreatorpro.ui.home.HomeScreen
import com.almus.appcreatorpro.ui.projects.ProjectsScreen
import com.almus.appcreatorpro.ui.settings.SettingsScreen
import com.almus.appcreatorpro.ui.splash.SplashScreen
import com.almus.appcreatorpro.ui.welcome.WelcomeScreen

/** Root composable: owns the navigation graph. Bottom bar is shown by the three main screens. */
@Composable
fun AlmusApp() {
    val navController = rememberNavController()
    val container = (LocalContext.current.applicationContext as AlmusApplication).container

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
                onOpenBuildHistory = { navController.navigate(Routes.BUILDS) },
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
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
                onOpenGitHub = { navController.navigate(Routes.GITHUB) },
                bottomBar = { AlmusBottomBar(currentRoute = Routes.SETTINGS, onNavigate = navigateTopLevel) },
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.EDITOR,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
        ) { entry ->
            EditorScreen(
                projectId = entry.arguments?.getString("projectId").orEmpty(),
                onBack = { navController.popBackStack() },
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
            )
        }
    }
}
