package com.stripe.android.ui.core.elements

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.stripe.android.cards.CardAccountRangeRepository
import com.stripe.android.cards.CardAccountRangeService
import com.stripe.android.cards.CardNumber
import com.stripe.android.cards.DefaultCardAccountRangeRepositoryFactory
import com.stripe.android.cards.DefaultStaticCardAccountRanges
import com.stripe.android.cards.StaticCardAccountRanges
import com.stripe.android.model.AccountRange
import com.stripe.android.model.CardBrand
import com.stripe.android.stripecardscan.cardscan.CardScanSheetResult
import com.stripe.android.ui.core.BuildConfig
import com.stripe.android.ui.core.R
import com.stripe.android.ui.core.asIndividualDigits
import com.stripe.android.uicore.elements.FieldError
import com.stripe.android.uicore.elements.SectionFieldErrorController
import com.stripe.android.uicore.elements.TextFieldController
import com.stripe.android.uicore.elements.TextFieldIcon
import com.stripe.android.uicore.elements.TextFieldState
import com.stripe.android.uicore.forms.FormFieldEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlin.coroutines.CoroutineContext
import com.stripe.android.R as PaymentsCoreR
import com.stripe.payments.model.R as PaymentsModelR
import com.stripe.payments.model.R as PaymentModelR

internal sealed class CardNumberController : TextFieldController, SectionFieldErrorController {
    abstract val cardBrandFlow: Flow<CardBrand>

    abstract val cardScanEnabled: Boolean

    @OptIn(ExperimentalComposeUiApi::class)
    override val autofillType: AutofillType = AutofillType.CreditCardNumber

    fun onCardScanResult(cardScanSheetResult: CardScanSheetResult) {
        // Don't need to populate the card number if the result is Canceled or Failed
        if (cardScanSheetResult is CardScanSheetResult.Completed) {
            onRawValueChange(cardScanSheetResult.scannedCard.pan)
        }
    }
}

internal class CardNumberEditableController constructor(
    private val cardTextFieldConfig: CardNumberConfig,
    context: Context,
    cardAccountRangeRepository: CardAccountRangeRepository,
    workContext: CoroutineContext,
    staticCardAccountRanges: StaticCardAccountRanges = DefaultStaticCardAccountRanges(),
    initialValue: String?,
    override val showOptionalLabel: Boolean = false,
    private val isEligibleForCardBrandChoice: Boolean = false,
) : CardNumberController() {

    constructor(
        cardTextFieldConfig: CardNumberConfig,
        context: Context,
        initialValue: String?,
        isEligibleForCardBrandChoice: Boolean,
    ) : this(
        cardTextFieldConfig,
        context,
        DefaultCardAccountRangeRepositoryFactory(context).create(),
        Dispatchers.IO,
        initialValue = initialValue,
        isEligibleForCardBrandChoice = isEligibleForCardBrandChoice,
    )

    override val capitalization: KeyboardCapitalization = cardTextFieldConfig.capitalization
    override val keyboardType: KeyboardType = cardTextFieldConfig.keyboard
    override val visualTransformation = cardTextFieldConfig.visualTransformation
    override val debugLabel = cardTextFieldConfig.debugLabel

    override val label: Flow<Int> = MutableStateFlow(cardTextFieldConfig.label)

    private val _fieldValue = MutableStateFlow("")
    override val fieldValue: Flow<String> = _fieldValue

    override val rawFieldValue: Flow<String> =
        _fieldValue.map { cardTextFieldConfig.convertToRaw(it) }

    // This makes the screen reader read out numbers digit by digit
    override val contentDescription: Flow<String> = _fieldValue.map { it.asIndividualDigits() }

    override val cardBrandFlow = _fieldValue.map {
        accountRangeService.accountRange?.brand ?: CardBrand.getCardBrands(it).firstOrNull()
            ?: CardBrand.Unknown
    }

    override val cardScanEnabled = true

    override val trailingIcon: Flow<TextFieldIcon?> = _fieldValue.map {
        val cardBrands = CardBrand.getCardBrands(it)
        if (isEligibleForCardBrandChoice && it.isNotEmpty()) {
            val selected = TextFieldIcon.Dropdown.Item(
                label = context.getString(PaymentsCoreR.string.stripe_card_brand_choice_no_selection),
                icon = PaymentModelR.drawable.stripe_ic_unknown
            )

            TextFieldIcon.Dropdown(
                title = context.getString(PaymentsCoreR.string.stripe_card_brand_choice_selection_header),
                currentItem = selected,
                items = listOf(selected) + cardBrands.map { brand ->
                    TextFieldIcon.Dropdown.Item(
                        label = brand.displayName,
                        icon = brand.icon
                    )
                },
                hide = it.length < 8
            )
        } else if (accountRangeService.accountRange != null) {
            TextFieldIcon.Trailing(accountRangeService.accountRange!!.brand.icon, isTintable = false)
        } else {
            val staticIcons = cardBrands.map { cardBrand ->
                TextFieldIcon.Trailing(cardBrand.icon, isTintable = false)
            }.filterIndexed { index, _ -> index < 3 }

            val animatedIcons = cardBrands.map { cardBrand ->
                TextFieldIcon.Trailing(cardBrand.icon, isTintable = false)
            }.filterIndexed { index, _ -> index > 2 }

            TextFieldIcon.MultiTrailing(
                staticIcons = staticIcons,
                animatedIcons = animatedIcons
            )
        }
    }

    private val _fieldState = combine(cardBrandFlow, _fieldValue) { brand, fieldValue ->
        cardTextFieldConfig.determineState(
            brand,
            fieldValue,
            accountRangeService.accountRange?.panLength ?: brand.getMaxLengthForCardNumber(
                fieldValue
            )
        )
    }
    override val fieldState: Flow<TextFieldState> = _fieldState

    private val _hasFocus = MutableStateFlow(false)

    @VisibleForTesting
    val accountRangeService = CardAccountRangeService(
        cardAccountRangeRepository,
        workContext,
        staticCardAccountRanges,
        object : CardAccountRangeService.AccountRangeResultListener {
            override fun onAccountRangeResult(newAccountRange: AccountRange?) {
                newAccountRange?.panLength?.let { panLength ->
                    (visualTransformation as CardNumberVisualTransformation).binBasedMaxPan =
                        panLength
                }
            }
        }
    )

    override val loading: Flow<Boolean> = accountRangeService.isLoading

    override val visibleError: Flow<Boolean> =
        combine(_fieldState, _hasFocus) { fieldState, hasFocus ->
            fieldState.shouldShowError(hasFocus)
        }

    /**
     * An error must be emitted if it is visible or not visible.
     **/
    override val error: Flow<FieldError?> =
        combine(visibleError, _fieldState) { visibleError, fieldState ->
            fieldState.getError()?.takeIf { visibleError }
        }

    override val isComplete: Flow<Boolean> = _fieldState.map { it.isValid() }

    override val formFieldValue: Flow<FormFieldEntry> =
        combine(isComplete, rawFieldValue) { complete, value ->
            FormFieldEntry(value, complete)
        }

    init {
        onRawValueChange(initialValue ?: "")

        // TODO(tillh-stripe)
        if (BuildConfig.DEBUG) {
            Log.d("CardNumberController", "Is eligible for CBC: $isEligibleForCardBrandChoice")
        }
    }

    /**
     * This is called when the value changed to is a display value.
     */
    override fun onValueChange(displayFormatted: String): TextFieldState? {
        _fieldValue.value = cardTextFieldConfig.filter(displayFormatted)
        val cardNumber = CardNumber.Unvalidated(displayFormatted)
        accountRangeService.onCardNumberChanged(cardNumber)

        return null
    }

    /**
     * This is called when the value changed to is a raw backing value, not a display value.
     */
    override fun onRawValueChange(rawValue: String) {
        onValueChange(cardTextFieldConfig.convertFromRaw(rawValue))
    }

    override fun onFocusChange(newHasFocus: Boolean) {
        _hasFocus.value = newHasFocus
    }
}
