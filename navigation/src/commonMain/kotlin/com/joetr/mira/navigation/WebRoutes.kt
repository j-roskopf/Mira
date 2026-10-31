package com.joetr.mira.navigation

public fun parseWebRoute(path: String): Any? {
    val segments = path.trim('/').split('/').filter { it.isNotBlank() }
    return when (segments.firstOrNull()) {
        null, "recipes" -> if (segments.size == 1) RecipesKey else segments.getOrNull(1)?.let { RecipeDetailKey(it) }
        "recipe" -> segments.getOrNull(1)?.let { RecipeDetailKey(it) }
        "share" -> segments.getOrNull(1)?.let { SharedRecipeKey(it) }
        "import" -> ImportKey
        "account" -> AccountKey
        else -> null
    }
}
