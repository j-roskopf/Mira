package com.joetr.mira.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import com.joetr.mira.domain.model.ExtractionConfidence
import com.joetr.mira.domain.model.PendingSyncEntry
import com.joetr.mira.domain.model.Recipe
import com.joetr.mira.domain.model.RecipeQuery
import com.joetr.mira.domain.model.RecipeStep
import com.joetr.mira.domain.model.SessionState
import com.joetr.mira.domain.model.SharedRecipe
import com.joetr.mira.domain.model.SharedRecipeLink
import com.joetr.mira.domain.model.SyncState
import com.joetr.mira.domain.model.SyncStatus
import com.joetr.mira.domain.model.ThemeMode
import com.joetr.mira.domain.repository.ImageRepository
import com.joetr.mira.domain.repository.ImportHistoryEntry
import com.joetr.mira.domain.repository.ImportRepository
import com.joetr.mira.domain.repository.RecipeRepository
import com.joetr.mira.domain.repository.SessionRepository
import com.joetr.mira.domain.repository.SharedRecipeRepository
import com.joetr.mira.domain.repository.SyncRepository
import com.joetr.mira.domain.repository.UserSettingsRepository
import com.joetr.mira.domain.usecase.DeleteRecipeUseCase
import com.joetr.mira.domain.usecase.CreateSharedRecipeUseCase
import com.joetr.mira.domain.usecase.ExportRecipesUseCase
import com.joetr.mira.domain.usecase.ImportMiraRecipesUseCase
import com.joetr.mira.domain.usecase.ImportMelaRecipesUseCase
import com.joetr.mira.domain.usecase.ImportRecipeFromUrlUseCase
import com.joetr.mira.domain.usecase.MergeLocalIntoAccountUseCase
import com.joetr.mira.domain.usecase.ObserveImportHistoryUseCase
import com.joetr.mira.domain.usecase.ObserveRecipeUseCase
import com.joetr.mira.domain.usecase.ObserveRecipesUseCase
import com.joetr.mira.domain.usecase.ObserveSessionUseCase
import com.joetr.mira.domain.usecase.ObserveSyncStateUseCase
import com.joetr.mira.domain.usecase.RevokeSharedRecipeUseCase
import com.joetr.mira.domain.usecase.ObserveShowStoreSearchLinksUseCase
import com.joetr.mira.domain.usecase.ObserveThemeModeUseCase
import com.joetr.mira.domain.usecase.SaveRecipeUseCase
import com.joetr.mira.domain.usecase.ScanRecipeFromImageUseCase
import com.joetr.mira.domain.usecase.SetShowStoreSearchLinksUseCase
import com.joetr.mira.domain.usecase.SetThemeModeUseCase
import com.joetr.mira.domain.usecase.SignOutUseCase
import com.joetr.mira.domain.usecase.ToggleFavouriteUseCase
import com.joetr.mira.feature.auth.AuthScreen
import com.joetr.mira.feature.auth.AuthViewModel
import com.joetr.mira.feature.cook.CookScreen
import com.joetr.mira.feature.cook.CookViewModel
import com.joetr.mira.feature.editor.EditorScreen
import com.joetr.mira.feature.editor.EditorViewModel
import com.joetr.mira.feature.import.ImportScreen
import com.joetr.mira.feature.import.ImportViewModel
import com.joetr.mira.feature.recipes.RecipeDetailScreen
import com.joetr.mira.feature.recipes.RecipeDetailViewModel
import com.joetr.mira.feature.recipes.RecipesScreen
import com.joetr.mira.feature.recipes.RecipesViewModel
import com.joetr.mira.feature.scan.ScanScreen
import com.joetr.mira.feature.scan.ScanViewModel
import com.joetr.mira.feature.settings.AccountScreen
import com.joetr.mira.feature.settings.SettingsViewModel
import androidx.compose.material3.MaterialTheme
import com.joetr.mira.ui.theme.MiraTheme
import com.joetr.mira.updates.AppUpdateService
import com.joetr.mira.updates.AppUpdateState
import com.joetr.mira.updates.AvailableUpdate
import io.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test

private val roboDir = File("src/desktopTest/resources/roborazzi")

private data class Viewport(val label: String, val width: Int, val height: Int)

/** Mobile / tablet / desktop capture sizes. */
private val viewports = listOf(
    Viewport("390", width = 390, height = 844),
    Viewport("720", width = 720, height = 1024),
    Viewport("1200", width = 1200, height = 800),
)

private const val now = 1_700_000_000_000L

private val foodDir = File("src/desktopTest/resources/food").absoluteFile

private fun foodFileUrl(name: String): String =
    File(foodDir, name).also { check(it.isFile) { "Missing food fixture: ${it.path}" } }.toURI().toString()

private val soupImageUrl = foodFileUrl("tomato_soup.jpg")
private val pastaImageUrl = foodFileUrl("garlic_pasta.jpg")
private val saladImageUrl = foodFileUrl("lemon_salad.jpg")

private val sampleRecipe = Recipe(
    id = "recipe-soup",
    ownerId = "guest",
    title = "Tomato Soup",
    description = "A cozy winter classic with roasted tomatoes and mira.",
    imageUrl = soupImageUrl,
    sourceUrl = "https://example.com/tomato-soup",
    servings = 4,
    prepMinutes = 15,
    cookMinutes = 30,
    ingredients = listOf(
        "2 lb ripe tomatoes",
        "1 yellow onion, diced",
        "3 cloves garlic",
        "2 cups vegetable broth",
        "Fresh mira",
    ),
    steps = listOf(
        RecipeStep("Roast tomatoes at 400°F until blistered.", minutes = 25),
        RecipeStep("Sauté onion and garlic until soft.", minutes = 8),
        RecipeStep("Simmer everything together, then blend smooth.", minutes = 15),
        RecipeStep("Finish with mira and salt to taste."),
    ),
    tags = listOf("soup", "comfort"),
    notes = "Serve with crusty bread.",
    isFavourite = true,
    createdAt = now,
    updatedAt = now,
)

private val sampleRecipes = listOf(
    sampleRecipe,
    sampleRecipe.copy(
        id = "recipe-pasta",
        title = "Garlic Pasta",
        description = "Weeknight pasta with olive oil and chili.",
        imageUrl = pastaImageUrl,
        sourceUrl = "https://example.com/garlic-pasta",
        prepMinutes = 10,
        cookMinutes = 20,
        tags = listOf("pasta"),
        isFavourite = false,
        ingredients = listOf("12 oz spaghetti", "6 cloves garlic", "Olive oil", "Chili flakes"),
        steps = listOf(
            RecipeStep("Boil pasta until al dente.", minutes = 10),
            RecipeStep("Bloom garlic in olive oil."),
            RecipeStep("Toss pasta with oil and chili."),
        ),
    ),
    sampleRecipe.copy(
        id = "recipe-salad",
        title = "Lemon Herb Salad",
        description = "Bright greens with lemon vinaigrette.",
        imageUrl = saladImageUrl,
        sourceUrl = null,
        prepMinutes = 10,
        cookMinutes = null,
        tags = listOf("salad"),
        isFavourite = false,
        ingredients = listOf("Mixed greens", "Lemon", "Olive oil", "Fresh herbs"),
        steps = listOf(
            RecipeStep("Whisk lemon with olive oil."),
            RecipeStep("Toss greens and herbs with dressing."),
        ),
    ),
)

private val fakeImageRepository = object : ImageRepository {
    override suspend fun saveLocalImage(recipeId: String, bytes: ByteArray) = "local-image"
    override suspend fun deleteLocalImage(localImageId: String) = Unit
    override suspend fun fetchAndStageRemoteImage(recipeId: String, remoteUrl: String): String? = null
    override suspend fun readLocalImage(localImageId: String): ByteArray? = null
}

private val fakeRecipeRepository = object : RecipeRepository {
    override fun observeRecipes(query: RecipeQuery) = flowOf(sampleRecipes)
    override fun observeRecipe(id: String) = flowOf(sampleRecipes.find { it.id == id })
    override suspend fun save(recipe: Recipe, syncImmediately: Boolean) = Unit
    override suspend fun delete(id: String) = Unit
    override suspend fun toggleFavourite(id: String) = Unit
    override suspend fun countByOwner(ownerId: String) = sampleRecipes.size
    override suspend fun getAllByOwner(ownerId: String) = sampleRecipes
    override suspend fun mergeLocalIntoAccount(localOwnerId: String, accountOwnerId: String) = 0
}

private val fakeSharedRecipeRepository = object : SharedRecipeRepository {
    override suspend fun create(recipe: Recipe) = SharedRecipeLink("share-token", "https://mira.joetr.com/share/share-token")
    override suspend fun get(token: String) = SharedRecipe(
        token = token,
        url = "https://mira.joetr.com/share/$token",
        title = sampleRecipe.title,
    )
    override suspend fun revoke(token: String) = Unit
}

private val fakeImportRepository = object : ImportRepository {
    override suspend fun extractFromUrl(url: String) = error("screenshot")
    override suspend fun extractFromOcrText(text: String) = error("screenshot")
    override fun observeImportHistory() = flowOf(
        listOf(
            ImportHistoryEntry(
                url = "https://example.com/tomato-soup",
                title = "Tomato Soup",
                confidence = ExtractionConfidence.FULL,
                importedAt = now,
            ),
            ImportHistoryEntry(
                url = "https://example.com/garlic-pasta",
                title = "Garlic Pasta",
                confidence = ExtractionConfidence.PARTIAL,
                importedAt = now - 86_400_000,
            ),
        ),
    )
}

private val fakeSessionRepository = object : SessionRepository {
    override fun observeSession() = flowOf(SessionState.Anonymous("guest"))
    override suspend fun ensureSession() = Unit
    override suspend fun resumePendingWebOAuth(): Boolean = false
    override suspend fun signInWithEmail(email: String, password: String) = Unit
    override suspend fun signUpWithEmail(email: String, password: String) = Unit
    override suspend fun resetPassword(email: String) = Unit
    override suspend fun signInWithGoogle() = Unit
    override suspend fun signOut() = Unit
    override suspend fun needsMergePrompt() = false to 0
    override suspend fun acceptMerge() = 0
    override suspend fun declineMerge() = Unit
}

private val fakeAuthenticatedSessionRepository = object : SessionRepository by fakeSessionRepository {
    override fun observeSession() = flowOf(SessionState.Authenticated("user-1", "joer@example.com"))
}

private val fakeUserSettingsRepository = object : UserSettingsRepository {
    override fun observeThemeMode() = flowOf(ThemeMode.SYSTEM)
    override suspend fun setThemeMode(mode: ThemeMode) = Unit
    override fun observeShowStoreSearchLinks() = flowOf(false)
    override suspend fun setShowStoreSearchLinks(enabled: Boolean) = Unit
}

private val fakeSyncRepository = object : SyncRepository {
    override fun observeSyncState() = flowOf(
        SyncState(
            status = SyncStatus.PENDING,
            pendingCount = 2,
            pendingEntries = listOf(
                PendingSyncEntry(id = "recipe-soup", title = "Tomato Soup", kind = "recipe"),
                PendingSyncEntry(id = "recipe-pasta", title = "Garlic Pasta", kind = "recipe"),
            ),
        ),
    )

    override suspend fun syncNow() = Unit
    override suspend fun syncAfterSignIn() = Unit
    override suspend fun retryFailed() = Unit
    override suspend fun dropPendingSync() = Unit
    override suspend fun dropPendingSyncEntry(id: String) = Unit
}

private val fakeUpdates = object : AppUpdateService {
    override val state: StateFlow<AppUpdateState> = MutableStateFlow(AppUpdateState.Current).asStateFlow()
    override val pendingInstallConfirmation: StateFlow<AvailableUpdate?> =
        MutableStateFlow<AvailableUpdate?>(null).asStateFlow()

    override suspend fun checkForUpdates(onFailure: (Throwable) -> Unit) = Unit
    override suspend fun installAvailableUpdate(onMessage: (String) -> Unit) = Unit
    override fun respondToInstallConfirmation(install: Boolean) = Unit
}

private fun importViewModel() = ImportViewModel(
    ImportRecipeFromUrlUseCase(fakeImportRepository),
    ImportMelaRecipesUseCase(
        fakeRecipeRepository,
        fakeImageRepository,
        fakeSyncRepository,
        ObserveSessionUseCase(fakeSessionRepository),
    ),
    ImportMiraRecipesUseCase(
        fakeRecipeRepository,
        fakeImageRepository,
        fakeSyncRepository,
        ObserveSessionUseCase(fakeSessionRepository),
    ),
    ExportRecipesUseCase(
        fakeRecipeRepository,
        fakeImageRepository,
        ObserveSessionUseCase(fakeSessionRepository),
    ),
    ObserveImportHistoryUseCase(fakeImportRepository),
)

class MainScreensDesktopTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun recipesScreenWidths() = captureAtWidths("Recipes", awaitText = "Tomato Soup", waitForImages = true) {
        RecipesScreen(
            RecipesViewModel(ObserveRecipesUseCase(fakeRecipeRepository)),
            onRecipeClick = {},
            onAddRecipe = {},
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun recipeDetailScreenWidths() = captureAtWidths("RecipeDetail", awaitText = "Tomato Soup", waitForImages = true) {
        RecipeDetailScreen(
            viewModel = RecipeDetailViewModel(
                ObserveRecipeUseCase(fakeRecipeRepository),
                DeleteRecipeUseCase(fakeRecipeRepository),
                ToggleFavouriteUseCase(fakeRecipeRepository),
                CreateSharedRecipeUseCase(fakeSharedRecipeRepository),
                RevokeSharedRecipeUseCase(fakeSharedRecipeRepository),
            ),
            recipeId = sampleRecipe.id,
            onBack = {},
            onEdit = {},
            onCook = {},
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun importScreenWidths() = captureAtWidths("Import", awaitText = "Tomato Soup") {
        ImportScreen(
            viewModel = importViewModel(),
            onExtracted = {},
            onScan = {},
            initialUrl = "https://example.com/tomato-soup",
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun accountScreenWidths() = captureAtWidths("Account", awaitText = "joer@example.com") {
        AccountScreen(
            SettingsViewModel(
                ObserveSessionUseCase(fakeAuthenticatedSessionRepository),
                ObserveSyncStateUseCase(fakeSyncRepository),
                fakeSyncRepository,
                ObserveThemeModeUseCase(fakeUserSettingsRepository),
                SetThemeModeUseCase(fakeUserSettingsRepository),
                ObserveShowStoreSearchLinksUseCase(fakeUserSettingsRepository),
                SetShowStoreSearchLinksUseCase(fakeUserSettingsRepository),
                SignOutUseCase(fakeAuthenticatedSessionRepository),
                fakeUpdates,
            ),
            onSignIn = {},
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun authScreenWidths() = captureAtWidths("Auth", awaitText = "Welcome back") {
        AuthScreen(
            viewModel = AuthViewModel(
                sessionRepository = fakeSessionRepository,
                mergeUseCase = MergeLocalIntoAccountUseCase(fakeSessionRepository),
            ),
            onDone = {},
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun editorScreenWidths() = captureAtWidths("Editor", awaitText = "Tomato Soup", waitForImages = true) {
        EditorScreen(
            viewModel = EditorViewModel(
                SaveRecipeUseCase(fakeRecipeRepository),
                ObserveSessionUseCase(fakeSessionRepository),
                ObserveRecipeUseCase(fakeRecipeRepository),
                fakeImageRepository,
            ),
            recipeId = sampleRecipe.id,
            extractedJson = null,
            onSaved = {},
            onBack = {},
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun cookScreenWidths() = captureAtWidths("Cook", awaitText = "2 lb ripe tomatoes") {
        CookScreen(
            viewModel = CookViewModel(ObserveRecipeUseCase(fakeRecipeRepository)),
            recipeId = sampleRecipe.id,
            onExit = {},
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun scanScreenWidths() = captureAtWidths("Scan", awaitText = "Scanning is only available on mobile.") {
        ScanScreen(
            viewModel = ScanViewModel(
                ScanRecipeFromImageUseCase(fakeImportRepository),
                fakeImageRepository,
            ),
            onExtracted = {},
            onBack = {},
        )
    }

    @OptIn(ExperimentalTestApi::class)
    private fun captureAtWidths(
        screen: String,
        awaitText: String? = null,
        waitForImages: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        listOf(false, true).forEach { darkTheme ->
            val themeLabel = if (darkTheme) "dark" else "light"
            viewports.forEach { viewport ->
                runDesktopComposeUiTest(width = viewport.width, height = viewport.height) {
                    setContent {
                        setSingletonImageLoaderFactory { context ->
                            ImageLoader.Builder(context).build()
                        }
                        MiraTheme(darkTheme = darkTheme) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background),
                            ) {
                                content()
                            }
                        }
                    }
                    waitForIdle()
                    if (awaitText != null) {
                        waitUntil(timeoutMillis = 5_000) {
                            onAllNodesWithText(awaitText, substring = true)
                                .fetchSemanticsNodes()
                                .isNotEmpty()
                        }
                    }
                    if (waitForImages) {
                        // Give Coil time to decode local food fixtures into AsyncImage.
                        delay(750)
                        waitForIdle()
                    }
                    onRoot().captureRoboImage(
                        roboDir.resolve("${screen}_${viewport.label}_$themeLabel.png").absolutePath,
                    )
                }
            }
        }
    }
}
