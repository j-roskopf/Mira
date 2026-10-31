package com.joetr.mira.ui.gallery

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.joetr.mira.domain.model.SyncStatus
import com.joetr.mira.ui.components.CheckableRow
import com.joetr.mira.ui.components.Chip
import com.joetr.mira.ui.components.IngredientLine
import com.joetr.mira.ui.components.MetaRow
import com.joetr.mira.ui.components.PillButton
import com.joetr.mira.ui.components.RecipeGridCard
import com.joetr.mira.ui.components.RecipeListRow
import com.joetr.mira.ui.components.ScreenTitleRow
import com.joetr.mira.ui.components.SyncStatusBadge
import com.joetr.mira.ui.theme.MiraSpacing
import com.joetr.mira.ui.theme.MiraTheme

@Composable
public fun ComponentGalleryScreen(darkTheme: Boolean) {
    MiraTheme(darkTheme = darkTheme) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Mira Components", style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.height(MiraSpacing.lg))
            ScreenTitleRow(title = "All", onAdd = {}, onMore = {})
            RecipeListRow(
                title = "Brownies with Peanut Butter",
                description = "Soft, fudgy brownies with a swirl of peanut butter on top.",
                minutes = 40,
                category = "Sweets",
                sourceHost = "vero.cooking",
                onClick = {},
            )
            RecipeGridCard("Tomato Mira Pasta", "30 min · Dinner", onClick = {})
            MetaRow("Servings", "4")
            IngredientLine("500 g light spelt flour")
            CheckableRow("black beans", checked = false, onCheckedChange = {}, note = "dried", quantity = "500g")
            CheckableRow("onion", checked = true, onCheckedChange = {}, quantity = "1")
            PillButton("Add recipe", onClick = {})
            Chip("Dinner", selected = true, onClick = {})
            SyncStatusBadge(SyncStatus.PENDING, 2)
        }
    }
}
