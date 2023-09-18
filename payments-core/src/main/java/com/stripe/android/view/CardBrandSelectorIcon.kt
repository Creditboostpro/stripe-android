package com.stripe.android.view

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stripe.android.R
import com.stripe.android.model.CardBrand
import com.stripe.android.uicore.elements.SingleChoiceDropdown

@Composable
internal fun CardBrandSelectorIcon(
    isLoading: Boolean,
    currentBrand: CardBrand,
    possibleBrands: List<CardBrand>,
    shouldShowCvc: Boolean,
    shouldShowErrorIcon: Boolean,
    tintColorInt: Int,
    modifier: Modifier = Modifier,
    onBrandSelected: (CardBrand?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    val icon = remember(isLoading, currentBrand, shouldShowCvc, shouldShowErrorIcon) {
        when {
            isLoading -> currentBrand.icon
            shouldShowErrorIcon -> currentBrand.errorIcon
            shouldShowCvc -> currentBrand.cvcIcon
            else -> currentBrand.icon
        }
    }

    val tint = remember(isLoading, currentBrand, shouldShowCvc, shouldShowErrorIcon) {
        when {
            isLoading -> Color(tintColorInt)
            shouldShowErrorIcon -> null
            shouldShowCvc -> Color(tintColorInt)
            else -> null
        }
    }

    val showDropdown = remember(possibleBrands) { possibleBrands.size > 1 }

    Box(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(enabled = showDropdown) {
                expanded = true
            },
        ) {
            Image(
                painter = painterResource(icon),
                colorFilter = tint?.let { ColorFilter.tint(it) },
                contentDescription = null,
                modifier = Modifier.requiredSize(width = 32.dp, height = 21.dp),
            )

            if (showDropdown) {
                Image(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    alpha = 0.4f,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        }

        if (showDropdown) {
            val noSelection = CardBrandChoice(
                label = stringResource(id = R.string.stripe_card_brand_choice_no_selection),
                icon = CardBrand.Unknown.icon
            )

            val brands = listOf(CardBrand.Unknown) + possibleBrands
            val choices = brands.map { brand ->
                brand.toChoice(noSelection)
            }

            SingleChoiceDropdown(
                title = stringResource(id = R.string.stripe_card_brand_choice_selection_header),
                expanded = expanded,
                currentChoice = currentBrand.toChoice(noSelection),
                choices = choices,
                onChoiceSelected = { choice ->
                    when (val choiceIndex = choices.indexOf(choice)) {
                        -1 -> Unit
                        0 -> onBrandSelected(null)
                        else -> onBrandSelected(possibleBrands[choiceIndex - 1])
                    }

                    expanded = false
                },
                onDismiss = { expanded = false },
            )
        }
    }
}

private fun CardBrand.toChoice(unknownBrand: CardBrandChoice): CardBrandChoice {
    return if (this == CardBrand.Unknown) {
        unknownBrand
    } else {
        CardBrandChoice(
            label = displayName,
            icon = icon
        )
    }
}
