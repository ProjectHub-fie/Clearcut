package com.projecthub.clearcut.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.projecthub.clearcut.core.designsystem.ClearCutColors
import com.projecthub.clearcut.feature.editor.EditorRoute
import com.projecthub.clearcut.feature.export.ExportRoute
import com.projecthub.clearcut.feature.home.HomeRoute
import com.projecthub.clearcut.feature.import.ImportRoute
import com.projecthub.clearcut.feature.projects.ProjectsRoute
import com.projecthub.clearcut.feature.scanner.ScannerRoute
import com.projecthub.clearcut.feature.settings.SettingsRoute

object Routes {
    const val HOME = "home"
    const val IMPORT = "import"
    const val SCANNER = "scanner/{mediaUri}"
    const val EDITOR = "editor/{mediaUri}"
    const val EXPORT = "export"
    const val PROJECTS = "projects"
    const val SETTINGS = "settings"

    fun buildScanner(mediaUri: String): String = "scanner/${encode(mediaUri)}"
    fun buildEditor(mediaUri: String): String = "editor/${encode(mediaUri)}"

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

private data class NavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

private val bottomItems = listOf(
    NavItem(Routes.HOME, "Home", Icons.Outlined.Home, Icons.Filled.Home),
    NavItem(Routes.SCANNER, "Scan", Icons.Outlined.DocumentScanner, Icons.Filled.DocumentScanner),
    NavItem(Routes.EDITOR, "Editor", Icons.Outlined.Edit, Icons.Filled.Edit),
    NavItem(Routes.PROJECTS, "Projects", Icons.Outlined.Folder, Icons.Filled.Folder),
    NavItem(Routes.SETTINGS, "Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
)

@Composable
fun ClearCutApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = { ClearCutBottomBar(currentRoute, navController) },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.HOME) {
                HomeRoute(
                    onNavigateToImport = { navController.navigate(Routes.IMPORT) },
                    onOpenScanner = { uri -> navController.navigate(Routes.buildScanner(uri)) },
                )
            }
            composable(Routes.IMPORT) {
                ImportRoute(
                    onBack = { navController.popBackStack() },
                    onImported = { mediaUri -> navController.navigate(Routes.buildScanner(mediaUri)) },
                    onOpenEditor = { mediaUri -> navController.navigate(Routes.buildEditor(mediaUri)) },
                )
            }
            composable(
                Routes.SCANNER,
                arguments = listOf(navArgument("mediaUri") { type = NavType.StringType }),
            ) { entry ->
                val uri = entry.arguments?.getString("mediaUri") ?: ""
                ScannerRoute(
                    mediaUri = uri,
                    onBack = { navController.popBackStack() },
                    onOpenEditor = { mediaUri -> navController.navigate(Routes.buildEditor(mediaUri)) },
                )
            }
            composable(
                Routes.EDITOR,
                arguments = listOf(navArgument("mediaUri") { type = NavType.StringType }),
            ) { entry ->
                val uri = entry.arguments?.getString("mediaUri") ?: ""
                EditorRoute(
                    mediaUri = uri,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.EXPORT) {
                ExportRoute(onBack = { navController.popBackStack() })
            }
            composable(Routes.PROJECTS) {
                ProjectsRoute(
                    onOpenProject = { uri -> navController.navigate(Routes.buildScanner(uri)) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsRoute()
            }
        }
    }
}

@Composable
private fun ClearCutBottomBar(currentRoute: String?, navController: NavHostController) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        bottomItems.forEach { item ->
            val isSelected = currentRoute?.startsWith(item.route) == true
            NavigationBarItem(
                selected = isSelected,
                onClick = {
                    navController.navigate(item.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(if (isSelected) item.selectedIcon else item.icon, contentDescription = item.label) },
                label = { Text(item.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = ClearCutColors.Accent,
                    selectedTextColor = ClearCutColors.Accent,
                    indicatorColor = ClearCutColors.Accent.copy(alpha = 0.14f),
                ),
            )
        }
    }
}