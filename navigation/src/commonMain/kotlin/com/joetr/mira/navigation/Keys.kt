package com.joetr.mira.navigation

import com.joetr.mira.domain.model.ExtractedRecipe
import kotlinx.serialization.Serializable

@Serializable
public data object RecipesKey

@Serializable
public data class RecipeDetailKey(val id: String)

@Serializable
public data class SharedRecipeKey(val token: String)

@Serializable
public data class EditorKey(
    val recipeId: String? = null,
    val extractedJson: String? = null,
)

@Serializable
public data object ImportKey

@Serializable
public data object ScanKey

@Serializable
public data class CookKey(val recipeId: String)

@Serializable
public data object AuthKey

@Serializable
public data object AccountKey

public sealed class TopLevelDestination {
    public data object Recipes : TopLevelDestination()
    public data object Import : TopLevelDestination()
    public data object Account : TopLevelDestination()
}

public fun ExtractedRecipe.toEditorJson(): String =
    kotlinx.serialization.json.Json.encodeToString(ExtractedRecipe.serializer(), this)

public fun editorJsonToExtracted(json: String?): ExtractedRecipe? =
    json?.let { kotlinx.serialization.json.Json.decodeFromString(ExtractedRecipe.serializer(), it) }
