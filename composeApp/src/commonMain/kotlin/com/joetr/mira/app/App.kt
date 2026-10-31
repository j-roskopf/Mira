package com.joetr.mira.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.joetr.mira.app.miraImages
import com.joetr.mira.di.AppGraph
import com.joetr.mira.feature.auth.AuthScreen
import com.joetr.mira.feature.cook.CookScreen
import com.joetr.mira.feature.editor.EditorScreen
import com.joetr.mira.feature.import.ImportScreen
import com.joetr.mira.feature.recipes.RecipeDetailScreen
import com.joetr.mira.feature.recipes.RecipesTabContent
import com.joetr.mira.feature.recipes.SharedRecipeScreen
import com.joetr.mira.feature.settings.AccountScreen
import com.joetr.mira.feature.settings.AppUpdateInstallDialog
import com.joetr.mira.feature.scan.ScanScreen
import com.joetr.mira.feature.scan.ScanViewModel
import com.joetr.mira.navigation.AccountKey
import com.joetr.mira.navigation.AuthKey
import com.joetr.mira.navigation.CookKey
import com.joetr.mira.navigation.EditorKey
import com.joetr.mira.navigation.ImportKey
import com.joetr.mira.navigation.RecipeDetailKey
import com.joetr.mira.navigation.SharedRecipeKey
import com.joetr.mira.navigation.ScanKey
import com.joetr.mira.navigation.RecipesKey
import com.joetr.mira.domain.model.SessionState
import com.joetr.mira.domain.model.ThemeMode
import com.joetr.mira.navigation.TopLevelDestination
import com.joetr.mira.ui.components.LocalShowStoreSearchLinks
import com.joetr.mira.navigation.AdaptiveScaffold
import com.joetr.mira.navigation.toEditorJson
import com.joetr.mira.platform.ImageCapture
import com.joetr.mira.platform.ShareIntentHolder
import com.joetr.mira.platform.consumePlatformShareUrl
import com.joetr.mira.ui.motion.LocalRecipeAnimatedVisibilityScope
import com.joetr.mira.ui.motion.LocalSharedTransitionScope
import com.joetr.mira.ui.motion.recipeSharedElementTransitionSpec
import com.joetr.mira.ui.theme.MiraTheme
import kotlinx.coroutines.launch

private sealed interface RecipeShellState {
    data object Scaffold : RecipeShellState
    data class NarrowDetail(val id: String) : RecipeShellState
}

private fun recipeShellState(
    widthDp: Int,
    currentKey: Any?,
    topLevel: TopLevelDestination,
): RecipeShellState {
    if (
        widthDp < 960 &&
        topLevel == TopLevelDestination.Recipes &&
        currentKey is RecipeDetailKey
    ) {
        return RecipeShellState.NarrowDetail(currentKey.id)
    }
    return RecipeShellState.Scaffold
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
public fun App(graph: AppGraph, initialWebPath: String? = null) {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory(httpClient = graph.imageHttpClient)) }
            .miraImages(imageRepository = graph.imageRepositoryPublic)
            .build()
    }

    val themeMode by graph.settingsViewModel.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val showStoreSearchLinks by graph.settingsViewModel.showStoreSearchLinks.collectAsState(initial = false)
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    CompositionLocalProvider(LocalShowStoreSearchLinks provides showStoreSearchLinks) {
        MiraTheme(darkTheme = darkTheme) {
        var topLevel by remember { mutableStateOf<TopLevelDestination>(TopLevelDestination.Recipes) }
        val backStack = remember {
            mutableStateListOf<Any>(
                initialWebPath?.let { com.joetr.mira.navigation.parseWebRoute(it) } ?: RecipesKey,
            )
        }
        var editorExtracted by remember { mutableStateOf<String?>(null) }
        var editorRecipeId by remember { mutableStateOf<String?>(null) }
        var cookRecipeId by remember { mutableStateOf<String?>(null) }
        var selectedRecipeId by remember { mutableStateOf<String?>(null) }
        var sharedImportUrl by remember { mutableStateOf<String?>(null) }
        val pendingUpdateInstall by graph.updates.pendingInstallConfirmation.collectAsState()
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            consumePlatformShareUrl()?.let { ShareIntentHolder.pendingUrl = it }
            ShareIntentHolder.pendingUrl?.let { url ->
                ShareIntentHolder.pendingUrl = null
                topLevel = TopLevelDestination.Import
                backStack.clear()
                backStack += ImportKey
                sharedImportUrl = url
            }
        }

        LaunchedEffect(Unit) {
            ShareIntentHolder.pendingSharedTokenFlow.collect { token ->
                if (token == null) return@collect
                ShareIntentHolder.pendingSharedToken = null
                backStack.clear()
                backStack += SharedRecipeKey(token)
            }
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthDp = maxWidth.value.toInt()
            val currentKey = backStack.lastOrNull()
            val canNavigateBack = when (currentKey) {
                is RecipeDetailKey, is ScanKey, is CookKey, is AuthKey, is EditorKey -> true
                is SharedRecipeKey -> true
                else -> widthDp >= 960 && selectedRecipeId != null
            }
            val navigationEventState = rememberNavigationEventState(NavigationEventInfo.None)
            NavigationBackHandler(
                state = navigationEventState,
                isBackEnabled = canNavigateBack,
                onBackCompleted = {
                    when (currentKey) {
                        is RecipeDetailKey, is ScanKey, is CookKey, is AuthKey, is EditorKey, is SharedRecipeKey -> {
                            backStack.removeLast()
                        }
                        else -> selectedRecipeId = null
                    }
                },
            )
            when (currentKey) {
                is SharedRecipeKey -> SharedRecipeScreen(
                    viewModel = graph.sharedRecipeViewModel,
                    token = currentKey.token,
                    onBack = { backStack.removeLast() },
                )
                is ScanKey -> ScanScreen(
                    viewModel = graph.scanViewModel,
                    onExtracted = { json -> backStack += EditorKey(extractedJson = json) },
                    onBack = { backStack.removeLast() },
                )
                is CookKey -> CookScreen(
                    viewModel = graph.cookViewModel,
                    recipeId = currentKey.recipeId,
                    onExit = { backStack.removeLast() },
                )
                is AuthKey -> AuthScreen(
                    viewModel = graph.authViewModel,
                    onDone = { backStack.removeLast() },
                )
                is EditorKey -> EditorScreen(
                    viewModel = graph.editorViewModel,
                    recipeId = currentKey.recipeId,
                    extractedJson = currentKey.extractedJson,
                    onSaved = { backStack.removeLast() },
                    onBack = { backStack.removeLast() },
                )
                else -> {
                    val session by graph.settingsViewModel.session.collectAsState(initial = null)
                    val accountLabel = when (val s = session) {
                        is SessionState.Authenticated -> s.email ?: "Account"
                        is SessionState.Anonymous -> "Guest"
                        is SessionState.LocalPending -> "Local"
                        null -> null
                    }
                    val shellState = recipeShellState(widthDp, currentKey, topLevel)
                    SharedTransitionLayout(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                    ) {
                        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                            AnimatedContent(
                                targetState = shellState,
                                contentKey = { it },
                                transitionSpec = recipeSharedElementTransitionSpec(),
                                label = "recipe_shell",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background),
                            ) { state ->
                                CompositionLocalProvider(
                                    LocalRecipeAnimatedVisibilityScope provides this@AnimatedContent,
                                ) {
                                    when (state) {
                                        is RecipeShellState.NarrowDetail -> RecipeDetailScreen(
                                            viewModel = graph.recipeDetailViewModel,
                                            recipeId = state.id,
                                            onBack = { backStack.removeLast() },
                                            onEdit = { id -> backStack += EditorKey(recipeId = id) },
                                            onCook = { id -> backStack += CookKey(recipeId = id) },
                                            onDeleted = { backStack.removeLast() },
                                        )
                                        RecipeShellState.Scaffold -> AdaptiveScaffold(
                                            widthDp = widthDp,
                                            selected = topLevel,
                                            onNavigate = { dest ->
                                                topLevel = dest
                                                selectedRecipeId = null
                                                backStack.clear()
                                                backStack += when (dest) {
                                                    TopLevelDestination.Recipes -> RecipesKey
                                                    TopLevelDestination.Import -> ImportKey
                                                    TopLevelDestination.Account -> AccountKey
                                                }
                                            },
                                            accountLabel = accountLabel,
                                        ) {
                                            when (topLevel) {
                                                TopLevelDestination.Recipes -> {
                                                    val openDetail: (String) -> Unit = { id ->
                                                        if (widthDp >= 960) selectedRecipeId = id
                                                        else backStack += RecipeDetailKey(id)
                                                    }
                                                    RecipesTabContent(
                                                        widthDp = widthDp,
                                                        selectedRecipeId = selectedRecipeId,
                                                        recipesViewModel = graph.recipesViewModel,
                                                        detailViewModel = graph.recipeDetailViewModel,
                                                        onRecipeClick = openDetail,
                                                        onCloseDetail = { selectedRecipeId = null },
                                                        onAddRecipe = { backStack += EditorKey() },
                                                        onEdit = { recipeId -> backStack += EditorKey(recipeId = recipeId) },
                                                        onCook = { recipeId -> backStack += CookKey(recipeId = recipeId) },
                                                        onDeleted = { selectedRecipeId = null },
                                                    )
                                                }
                                                TopLevelDestination.Import -> ImportScreen(
                                                    viewModel = graph.importViewModel,
                                                    onExtracted = { json -> backStack += EditorKey(extractedJson = json) },
                                                    onScan = if (ImageCapture.isAvailable) ({ backStack += ScanKey }) else null,
                                                    initialUrl = sharedImportUrl,
                                                )
                                                TopLevelDestination.Account -> AccountScreen(
                                                    viewModel = graph.settingsViewModel,
                                                    onSignIn = { backStack += AuthKey },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        pendingUpdateInstall?.let { update ->
            AppUpdateInstallDialog(
                update = update,
                onDismiss = { graph.updates.respondToInstallConfirmation(false) },
                onConfirm = { graph.updates.respondToInstallConfirmation(true) },
            )
        }
    }
    }
}
