package com.joetr.mira.di

import com.joetr.mira.data.auth.createFirebaseSessionStore
import com.joetr.mira.data.auth.DefaultSessionRepository
import com.joetr.mira.data.image.DefaultImageRepository
import com.joetr.mira.data.recipe.DefaultUserSettingsRepository
import com.joetr.mira.data.recipe.DefaultImportRepository
import com.joetr.mira.data.recipe.DefaultRecipeRepository
import com.joetr.mira.data.recipe.DefaultSharedRecipeRepository
import com.joetr.mira.data.recipe.DefaultSyncRepository
import com.joetr.mira.data.recipe.sync.ImageUploadWorker
import com.joetr.mira.data.recipe.sync.RecipeSyncService
import com.joetr.mira.database.MiraDataLayer
import com.joetr.mira.domain.usecase.DeleteRecipeUseCase
import com.joetr.mira.domain.usecase.CreateSharedRecipeUseCase
import com.joetr.mira.domain.usecase.ExportRecipesUseCase
import com.joetr.mira.domain.usecase.ImportMiraRecipesUseCase
import com.joetr.mira.domain.usecase.ImportMelaRecipesUseCase
import com.joetr.mira.domain.usecase.ImportRecipeFromUrlUseCase
import com.joetr.mira.domain.usecase.GetSharedRecipeUseCase
import com.joetr.mira.domain.usecase.MergeLocalIntoAccountUseCase
import com.joetr.mira.domain.usecase.ObserveImportHistoryUseCase
import com.joetr.mira.domain.usecase.ObserveRecipeUseCase
import com.joetr.mira.domain.usecase.ObserveRecipesUseCase
import com.joetr.mira.domain.usecase.ObserveSessionUseCase
import com.joetr.mira.domain.usecase.ObserveSyncStateUseCase
import com.joetr.mira.domain.usecase.ObserveShowStoreSearchLinksUseCase
import com.joetr.mira.domain.usecase.ObserveThemeModeUseCase
import com.joetr.mira.domain.usecase.SaveRecipeUseCase
import com.joetr.mira.domain.usecase.SaveSharedRecipeCopyUseCase
import com.joetr.mira.domain.usecase.ScanRecipeFromImageUseCase
import com.joetr.mira.domain.usecase.RevokeSharedRecipeUseCase
import com.joetr.mira.domain.usecase.SetShowStoreSearchLinksUseCase
import com.joetr.mira.domain.usecase.SetThemeModeUseCase
import com.joetr.mira.domain.usecase.SignOutUseCase
import com.joetr.mira.domain.usecase.ToggleFavouriteUseCase
import com.joetr.mira.feature.auth.AuthViewModel
import com.joetr.mira.feature.cook.CookViewModel
import com.joetr.mira.feature.editor.EditorViewModel
import com.joetr.mira.feature.import.ImportViewModel
import com.joetr.mira.feature.recipes.RecipeDetailViewModel
import com.joetr.mira.feature.recipes.RecipesViewModel
import com.joetr.mira.feature.recipes.SharedRecipeViewModel
import com.joetr.mira.feature.scan.ScanViewModel
import com.joetr.mira.feature.settings.SettingsViewModel
import com.joetr.mira.network.MiraFirebase
import com.joetr.mira.network.RecipeExtractor
import com.joetr.mira.network.createMiraHttpClient
import com.joetr.mira.network.createMiraImageHttpClient
import com.joetr.mira.platform.AppLifecycleObserver
import com.joetr.mira.updates.createAppUpdateService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

public class AppGraph(
    dataLayer: MiraDataLayer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val httpClient = createMiraHttpClient()
    public val imageHttpClient = createMiraImageHttpClient()
    private val firebase = MiraFirebase.create(
        httpClient = httpClient,
        sessionStore = createFirebaseSessionStore(dataLayer.database),
    )
    private val imageRepository = DefaultImageRepository(dataLayer.database, imageHttpClient, firebase)
    private val syncService = RecipeSyncService(dataLayer.database, firebase, imageRepository, scope)
    private val recipeRepository = DefaultRecipeRepository(dataLayer.database, syncService, imageRepository)
    private val sharedRecipeRepository = DefaultSharedRecipeRepository(firebase)
    private val syncRepository = DefaultSyncRepository(syncService)
    private val sessionRepository = DefaultSessionRepository(
        dataLayer.database,
        recipeRepository,
        firebase,
        syncRepository,
    )
    private val saveRecipeUseCase = SaveRecipeUseCase(recipeRepository)
    private val createSharedRecipeUseCase = CreateSharedRecipeUseCase(sharedRecipeRepository)
    private val getSharedRecipeUseCase = GetSharedRecipeUseCase(sharedRecipeRepository)
    private val revokeSharedRecipeUseCase = RevokeSharedRecipeUseCase(sharedRecipeRepository)
    private val recipeExtractor = RecipeExtractor(firebase)
    private val importRepository = DefaultImportRepository(dataLayer.database, recipeExtractor)
    public val imageRepositoryPublic: DefaultImageRepository get() = imageRepository
    private val imageUploadWorker = ImageUploadWorker(imageRepository, syncService, scope)
    private val userSettingsRepository = DefaultUserSettingsRepository(dataLayer.database, scope)
    public val updates = createAppUpdateService(scope, httpClient)

    private val startupJob = scope.launch {
        firebase.preloadPersistedSession()
        val signedInViaOAuth = sessionRepository.resumePendingWebOAuth()
        sessionRepository.ensureSession()
        if (signedInViaOAuth) {
            syncRepository.syncAfterSignIn()
        } else {
            syncService.syncNow()
        }
        imageUploadWorker.start()
        updates.checkForUpdates()
    }

    init {
        AppLifecycleObserver.onForeground = {
            scope.launch { syncService.syncIfStale() }
        }
    }

    internal suspend fun awaitStartup() {
        startupJob.join()
    }

    public val recipesViewModel: RecipesViewModel = RecipesViewModel(ObserveRecipesUseCase(recipeRepository))
    public val recipeDetailViewModel: RecipeDetailViewModel = RecipeDetailViewModel(
        observeRecipe = ObserveRecipeUseCase(recipeRepository),
        deleteRecipe = DeleteRecipeUseCase(recipeRepository),
        toggleFavouriteUseCase = ToggleFavouriteUseCase(recipeRepository),
        createSharedRecipe = createSharedRecipeUseCase,
        revokeSharedRecipe = revokeSharedRecipeUseCase,
    )
    public val sharedRecipeViewModel: SharedRecipeViewModel = SharedRecipeViewModel(
        getSharedRecipe = getSharedRecipeUseCase,
        saveSharedRecipeCopy = SaveSharedRecipeCopyUseCase(
            saveRecipe = saveRecipeUseCase,
            observeSession = ObserveSessionUseCase(sessionRepository),
        ),
    )
    public val importViewModel: ImportViewModel = ImportViewModel(
        ImportRecipeFromUrlUseCase(importRepository),
        ImportMelaRecipesUseCase(recipeRepository, imageRepository, syncRepository, ObserveSessionUseCase(sessionRepository)),
        ImportMiraRecipesUseCase(recipeRepository, imageRepository, syncRepository, ObserveSessionUseCase(sessionRepository)),
        ExportRecipesUseCase(recipeRepository, imageRepository, ObserveSessionUseCase(sessionRepository)),
        ObserveImportHistoryUseCase(importRepository),
    )
    public val editorViewModel: EditorViewModel = EditorViewModel(
        saveRecipe = saveRecipeUseCase,
        observeSession = ObserveSessionUseCase(sessionRepository),
        observeRecipe = ObserveRecipeUseCase(recipeRepository),
        imageRepository = imageRepository,
    )
    public val cookViewModel: CookViewModel = CookViewModel(ObserveRecipeUseCase(recipeRepository))
    public val authViewModel: AuthViewModel = AuthViewModel(
        sessionRepository,
        MergeLocalIntoAccountUseCase(sessionRepository),
    )
    public val scanViewModel: ScanViewModel = ScanViewModel(
        ScanRecipeFromImageUseCase(importRepository),
        imageRepository,
    )
    public val settingsViewModel: SettingsViewModel = SettingsViewModel(
        ObserveSessionUseCase(sessionRepository),
        ObserveSyncStateUseCase(syncRepository),
        syncRepository,
        ObserveThemeModeUseCase(userSettingsRepository),
        SetThemeModeUseCase(userSettingsRepository),
        ObserveShowStoreSearchLinksUseCase(userSettingsRepository),
        SetShowStoreSearchLinksUseCase(userSettingsRepository),
        SignOutUseCase(sessionRepository),
        updates,
    )

}

public suspend fun createMiraAppGraph(): AppGraph {
    val dataLayer = com.joetr.mira.database.createMiraDataLayer()
    val graph = AppGraph(dataLayer)
    graph.awaitStartup()
    return graph
}
