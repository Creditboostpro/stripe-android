package com.stripe.android.financialconnections.features.networkinglinksignup

import android.webkit.URLUtil
import com.airbnb.mvrx.Async
import com.airbnb.mvrx.MavericksState
import com.airbnb.mvrx.MavericksViewModelFactory
import com.airbnb.mvrx.Uninitialized
import com.airbnb.mvrx.ViewModelContext
import com.stripe.android.core.Logger
import com.stripe.android.financialconnections.R
import com.stripe.android.financialconnections.analytics.FinancialConnectionsAnalyticsEvent.Click
import com.stripe.android.financialconnections.analytics.FinancialConnectionsAnalyticsEvent.NetworkingNewConsumer
import com.stripe.android.financialconnections.analytics.FinancialConnectionsAnalyticsEvent.NetworkingReturningConsumer
import com.stripe.android.financialconnections.analytics.FinancialConnectionsAnalyticsEvent.PaneLoaded
import com.stripe.android.financialconnections.analytics.FinancialConnectionsAnalyticsTracker
import com.stripe.android.financialconnections.analytics.logError
import com.stripe.android.financialconnections.domain.GetCachedAccounts
import com.stripe.android.financialconnections.domain.GetManifest
import com.stripe.android.financialconnections.domain.LookupAccount
import com.stripe.android.financialconnections.domain.SaveAccountToLink
import com.stripe.android.financialconnections.domain.SynchronizeFinancialConnectionsSession
import com.stripe.android.financialconnections.features.common.getBusinessName
import com.stripe.android.financialconnections.features.networkinglinksignup.NetworkingLinkSignupState.ViewEffect.OpenBottomSheet
import com.stripe.android.financialconnections.features.networkinglinksignup.NetworkingLinkSignupState.ViewEffect.OpenUrl
import com.stripe.android.financialconnections.model.FinancialConnectionsSessionManifest
import com.stripe.android.financialconnections.model.FinancialConnectionsSessionManifest.Pane
import com.stripe.android.financialconnections.model.NetworkingLinkSignupPane
import com.stripe.android.financialconnections.navigation.Destination.NetworkingSaveToLinkVerification
import com.stripe.android.financialconnections.navigation.Destination.Success
import com.stripe.android.financialconnections.navigation.NavigationManager
import com.stripe.android.financialconnections.presentation.BaseViewModel
import com.stripe.android.financialconnections.presentation.TopAppBarHost
import com.stripe.android.financialconnections.repository.SaveToLinkWithStripeSucceededRepository
import com.stripe.android.financialconnections.ui.FinancialConnectionsSheetNativeActivity
import com.stripe.android.financialconnections.ui.components.TopAppBarState
import com.stripe.android.financialconnections.utils.ConflatedJob
import com.stripe.android.financialconnections.utils.UriUtils
import com.stripe.android.financialconnections.utils.isCancellationError
import com.stripe.android.model.ConsumerSessionLookup
import com.stripe.android.uicore.elements.EmailConfig
import com.stripe.android.uicore.elements.InputController
import com.stripe.android.uicore.elements.PhoneNumberController
import com.stripe.android.uicore.elements.SimpleTextFieldController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Date
import javax.inject.Inject

internal class NetworkingLinkSignupViewModel @Inject constructor(
    initialState: NetworkingLinkSignupState,
    topAppBarHost: TopAppBarHost,
    private val saveToLinkWithStripeSucceeded: SaveToLinkWithStripeSucceededRepository,
    private val saveAccountToLink: SaveAccountToLink,
    private val lookupAccount: LookupAccount,
    private val uriUtils: UriUtils,
    private val getCachedAccounts: GetCachedAccounts,
    private val eventTracker: FinancialConnectionsAnalyticsTracker,
    private val getManifest: GetManifest,
    private val sync: SynchronizeFinancialConnectionsSession,
    private val navigationManager: NavigationManager,
    private val logger: Logger
) : BaseViewModel<NetworkingLinkSignupState>(initialState, topAppBarHost) {

    private var searchJob = ConflatedJob()

    init {
        observeAsyncs()
        suspend {
            val manifest = getManifest()
            val content = requireNotNull(sync().text?.networkingLinkSignupPane)
            eventTracker.track(PaneLoaded(PANE))
            NetworkingLinkSignupState.Payload(
                content = content,
                merchantName = manifest.getBusinessName(),
                emailController = SimpleTextFieldController(
                    textFieldConfig = EmailConfig(label = R.string.stripe_networking_signup_email_label),
                    initialValue = manifest.accountholderCustomerEmailAddress,
                    showOptionalLabel = false
                ),
                phoneController = PhoneNumberController
                    .createPhoneNumberController(manifest.accountholderPhoneNumber ?: ""),
            )
        }.execute { copy(payload = it) }
    }

    override fun mapStateToTopAppBarState(state: NetworkingLinkSignupState): TopAppBarState {
        return TopAppBarState(
            pane = Pane.NETWORKING_LINK_SIGNUP_PANE,
            hideStripeLogo = false,
            testMode = true,
            allowBackNavigation = false,
        )
    }

    private fun observeAsyncs() {
        observePayloadResult()
        observeSaveAccountResult()
        observeLookupAccountResult()
    }

    private fun observeLookupAccountResult() {
        onAsync(
            NetworkingLinkSignupState::lookupAccount,
            onSuccess = { consumerSession ->
                if (consumerSession.exists) {
                    eventTracker.track(NetworkingReturningConsumer(PANE))
                    navigateToLinkVerification()
                } else {
                    eventTracker.track(NetworkingNewConsumer(PANE))
                }
            },
            onFail = { error ->
                eventTracker.logError(
                    extraMessage = "Error looking up account",
                    error = error,
                    logger = logger,
                    pane = PANE
                )
            },
        )
    }

    private fun observeSaveAccountResult() {
        onAsync(
            NetworkingLinkSignupState::saveAccountToLink,
            onSuccess = {
                saveToLinkWithStripeSucceeded.set(true)
                navigationManager.tryNavigateTo(Success(referrer = PANE))
            },
            onFail = { error ->
                saveToLinkWithStripeSucceeded.set(false)
                eventTracker.logError(
                    extraMessage = "Error saving account to Link",
                    error = error,
                    logger = logger,
                    pane = PANE
                )
                navigationManager.tryNavigateTo(Success(referrer = PANE))
            },
        )
    }

    private fun observePayloadResult() {
        onAsync(
            NetworkingLinkSignupState::payload,
            onSuccess = { payload ->
                // Observe controllers and set current form in state.
                viewModelScope.launch {
                    payload.emailController
                        .validFormFieldState()
                        .collectLatest(::onEmailEntered)
                }
                viewModelScope.launch {
                    payload.phoneController.validFormFieldState().collectLatest {
                        setState { copy(validPhone = it) }
                    }
                }
            },
            onFail = { error ->
                eventTracker.logError(
                    extraMessage = "Error fetching payload",
                    error = error,
                    logger = logger,
                    pane = PANE
                )
            },
        )
    }

    /**
     * @param validEmail valid email, or null if entered email is invalid.
     */
    private suspend fun onEmailEntered(
        validEmail: String?
    ) {
        setState { copy(validEmail = validEmail) }
        if (validEmail != null) {
            logger.debug("VALID EMAIL ADDRESS $validEmail.")
            searchJob += suspend {
                delay(getLookupDelayMs(validEmail))
                lookupAccount(validEmail)
            }.execute { copy(lookupAccount = if (it.isCancellationError()) Uninitialized else it) }
        } else {
            setState { copy(lookupAccount = Uninitialized) }
        }
    }

    /**
     * A valid e-mail will transition the user to the phone number field (sometimes prematurely),
     * so we increase debounce if there's a high chance the e-mail is not yet finished being typed
     * (high chance of not finishing == not .com suffix)
     *
     * @return delay in milliseconds
     */
    private fun getLookupDelayMs(validEmail: String) =
        if (validEmail.endsWith(".com")) SEARCH_DEBOUNCE_FINISHED_EMAIL_MS else SEARCH_DEBOUNCE_MS

    fun onSkipClick() = viewModelScope.launch {
        eventTracker.track(Click(eventName = "click.not_now", pane = PANE))
        navigationManager.tryNavigateTo(Success(referrer = PANE))
    }

    fun onSaveAccount() {
        withState { state ->
            eventTracker.track(Click(eventName = "click.save_to_link", pane = PANE))

            val hasExistingAccount = state.lookupAccount()?.exists == true
            if (hasExistingAccount) {
                navigateToLinkVerification()
            } else {
                saveNewAccount()
            }
        }
    }

    private fun saveNewAccount() {
        suspend {
            eventTracker.track(Click(eventName = "click.save_to_link", pane = PANE))
            val state = awaitState()
            val selectedAccounts = getCachedAccounts()
            val phoneController = state.payload()!!.phoneController
            require(state.valid) { "Form invalid! ${state.validEmail} ${state.validPhone}" }
            saveAccountToLink.new(
                country = phoneController.getCountryCode(),
                email = state.validEmail!!,
                phoneNumber = phoneController.getE164PhoneNumber(state.validPhone!!),
                selectedAccounts = selectedAccounts.map { it.id },
            )
        }.execute { copy(saveAccountToLink = it) }
    }

    private fun navigateToLinkVerification() {
        navigationManager.tryNavigateTo(NetworkingSaveToLinkVerification(referrer = PANE))
    }

    fun onClickableTextClick(uri: String) = viewModelScope.launch {
        // if clicked uri contains an eventName query param, track click event.
        uriUtils.getQueryParameter(uri, "eventName")?.let { eventName ->
            eventTracker.track(Click(eventName, pane = PANE))
        }
        val date = Date()
        if (URLUtil.isNetworkUrl(uri)) {
            setState { copy(viewEffect = OpenUrl(uri, date.time)) }
        } else {
            val managedUri = NetworkingLinkSignupClickableText.values()
                .firstOrNull { uriUtils.compareSchemeAuthorityAndPath(it.value, uri) }
            when (managedUri) {
                NetworkingLinkSignupClickableText.LEGAL_DETAILS -> {
                    setState {
                        copy(viewEffect = OpenBottomSheet(date.time))
                    }
                }

                null -> logger.error("Unrecognized clickable text: $uri")
            }
        }
    }

    /**
     * Emits the entered [InputController.formFieldValue] if the form is valid, null otherwise.
     */
    private fun InputController.validFormFieldState() =
        formFieldValue
            .map { it.takeIf { it.isComplete }?.value }
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

    fun onViewEffectLaunched() {
        setState { copy(viewEffect = null) }
    }

    companion object :
        MavericksViewModelFactory<NetworkingLinkSignupViewModel, NetworkingLinkSignupState> {

        override fun create(
            viewModelContext: ViewModelContext,
            state: NetworkingLinkSignupState
        ): NetworkingLinkSignupViewModel {
            val parentViewModel = viewModelContext.activity<FinancialConnectionsSheetNativeActivity>().viewModel
            return parentViewModel
                .activityRetainedComponent
                .networkingLinkSignupSubcomponent
                .initialState(state)
                .topAppBarHost(parentViewModel)
                .build()
                .viewModel
        }

        private const val SEARCH_DEBOUNCE_MS = 1000L
        private const val SEARCH_DEBOUNCE_FINISHED_EMAIL_MS = 300L
        internal val PANE = Pane.NETWORKING_LINK_SIGNUP_PANE
    }
}

internal data class NetworkingLinkSignupState(
    val payload: Async<Payload> = Uninitialized,
    val validEmail: String? = null,
    val validPhone: String? = null,
    val saveAccountToLink: Async<FinancialConnectionsSessionManifest> = Uninitialized,
    val lookupAccount: Async<ConsumerSessionLookup> = Uninitialized,
    val viewEffect: ViewEffect? = null
) : MavericksState {

    val showFullForm: Boolean
        get() = lookupAccount()?.let { !it.exists } ?: false

    val valid: Boolean
        get() {
            val hasExistingAccount = lookupAccount()?.exists == true
            return validEmail != null && (hasExistingAccount || validPhone != null)
        }

    data class Payload(
        val merchantName: String?,
        val emailController: SimpleTextFieldController,
        val phoneController: PhoneNumberController,
        val content: NetworkingLinkSignupPane
    )

    sealed class ViewEffect {
        data class OpenUrl(
            val url: String,
            val id: Long
        ) : ViewEffect()

        data class OpenBottomSheet(
            val id: Long
        ) : ViewEffect()
    }
}

private enum class NetworkingLinkSignupClickableText(val value: String) {
    LEGAL_DETAILS("stripe://legal-details-notice"),
}
