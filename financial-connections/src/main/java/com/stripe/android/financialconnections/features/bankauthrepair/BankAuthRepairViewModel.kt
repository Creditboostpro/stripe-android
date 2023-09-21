package com.stripe.android.financialconnections.features.bankauthrepair

import android.webkit.URLUtil
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.MavericksViewModel
import com.airbnb.mvrx.MavericksViewModelFactory
import com.airbnb.mvrx.ViewModelContext
import com.stripe.android.core.Logger
import com.stripe.android.financialconnections.analytics.FinancialConnectionsAnalyticsTracker
import com.stripe.android.financialconnections.analytics.FinancialConnectionsEvent.Click
import com.stripe.android.financialconnections.analytics.FinancialConnectionsEvent.PaneLoaded
import com.stripe.android.financialconnections.analytics.logError
import com.stripe.android.financialconnections.di.APPLICATION_ID
import com.stripe.android.financialconnections.domain.CompleteRepairSession
import com.stripe.android.financialconnections.domain.CreateRepairSession
import com.stripe.android.financialconnections.domain.GetCachedAccounts
import com.stripe.android.financialconnections.domain.GetCachedConsumerSession
import com.stripe.android.financialconnections.domain.GetManifest
import com.stripe.android.financialconnections.domain.SelectNetworkedAccount
import com.stripe.android.financialconnections.domain.UpdateLocalManifest
import com.stripe.android.financialconnections.exception.WebAuthFlowFailedException
import com.stripe.android.financialconnections.features.partnerauth.PartnerAuthState
import com.stripe.android.financialconnections.features.partnerauth.PartnerAuthState.ClickableText
import com.stripe.android.financialconnections.features.partnerauth.PartnerAuthState.ViewEffect.OpenBottomSheet
import com.stripe.android.financialconnections.features.partnerauth.PartnerAuthState.ViewEffect.OpenPartnerAuth
import com.stripe.android.financialconnections.features.partnerauth.PartnerAuthState.ViewEffect.OpenUrl
import com.stripe.android.financialconnections.model.FinancialConnectionsAuthorizationRepairSession
import com.stripe.android.financialconnections.model.FinancialConnectionsAuthorizationSession
import com.stripe.android.financialconnections.model.FinancialConnectionsSessionManifest.Pane
import com.stripe.android.financialconnections.navigation.Destination
import com.stripe.android.financialconnections.navigation.Destination.LinkAccountPicker
import com.stripe.android.financialconnections.navigation.Destination.ManualEntry
import com.stripe.android.financialconnections.navigation.Destination.Reset
import com.stripe.android.financialconnections.navigation.NavigationManager
import com.stripe.android.financialconnections.presentation.WebAuthFlowState
import com.stripe.android.financialconnections.repository.CoreAuthorizationPendingNetworkingRepairRepository
import com.stripe.android.financialconnections.repository.SaveToLinkWithStripeSucceededRepository
import com.stripe.android.financialconnections.ui.FinancialConnectionsSheetNativeActivity
import com.stripe.android.financialconnections.utils.UriUtils
import kotlinx.coroutines.launch
import java.util.Date
import javax.inject.Inject
import javax.inject.Named

@Suppress("LongParameterList")
internal class BankAuthRepairViewModel @Inject constructor(
    private val eventTracker: FinancialConnectionsAnalyticsTracker,
    @Named(APPLICATION_ID) private val applicationId: String,
    private val uriUtils: UriUtils,
    private val createRepairSession: CreateRepairSession,
    private val updateLocalManifest: UpdateLocalManifest,
    private val getCachedConsumerSession: GetCachedConsumerSession,
    private val coreAuthorizationPendingNetworkingRepair: CoreAuthorizationPendingNetworkingRepairRepository,
    private val selectNetworkedAccount: SelectNetworkedAccount,
    private val completeRepairSession: CompleteRepairSession,
    private val getManifest: GetManifest,
    private val saveToLinkWithStripeSucceeded: SaveToLinkWithStripeSucceededRepository,
    private val getCachedAccounts: GetCachedAccounts,
    private val navigationManager: NavigationManager,
    private val logger: Logger,
    initialState: PartnerAuthState
) : MavericksViewModel<PartnerAuthState>(initialState) {
    init {
        observePayload()
        suspend {
            val selectedAccount = getCachedAccounts().first()
            val coreAuthorization = requireNotNull(coreAuthorizationPendingNetworkingRepair.get())
            val repairSession = createRepairSession(coreAuthorization)
            updateLocalManifest { it.copy(activeInstitution = repairSession.institution) }
            PartnerAuthState.Payload(
                authSession = repairSession.toAuthSession(),
                institution = requireNotNull(repairSession.institution),
                isStripeDirect = getManifest().isStripeDirect ?: false,
                repairPayload = PartnerAuthState.RepairPayload(
                    consumerSession = requireNotNull(getCachedConsumerSession()).clientSecret,
                    coreAuthorization = coreAuthorization,
                    selectedAccountId = selectedAccount.id,
                )
            )
        }.execute {
            copy(payload = it)
        }
    }

    fun FinancialConnectionsAuthorizationRepairSession.toAuthSession() =
        FinancialConnectionsAuthorizationSession(
            id = id,
            nextPane = Pane.SUCCESS,
            flow = flow,
            institutionSkipAccountSelection = null,
            showPartnerDisclosure = null,
            skipAccountSelection = null,
            url = url,
            urlQrCode = null,
            _isOAuth = isOAuth,
            display = display
        )

    private fun observePayload() {
        onAsync(
            asyncProp = PartnerAuthState::payload,
            onSuccess = {
                // launch auth for non-OAuth (skip pre-pane).
                eventTracker.track(PaneLoaded(Pane.BANK_AUTH_REPAIR))
                if (!it.authSession.isOAuth) launchAuthInBrowser()
            },
            onFail = {
                eventTracker.logError(
                    extraMessage = "failed fetching payload / posting AuthSession",
                    error = it,
                    logger = logger,
                    pane = PANE
                )
            }
        )
    }

    fun onLaunchAuthClick() {
        viewModelScope.launch {
            launchAuthInBrowser()
        }
    }

    private suspend fun launchAuthInBrowser() = kotlin.runCatching {
        val authSession = requireNotNull(awaitState().payload()).authSession
        val url = requireNotNull(authSession.browserReadyUrl(applicationId))
        setState { copy(viewEffect = OpenPartnerAuth(url)) }
    }.onFailure {
        eventTracker.logError(
            extraMessage = "failed retrieving auth session url from cache",
            error = it,
            logger = logger,
            pane = PANE
        )
        setState { copy(authenticationStatus = Fail(it)) }
    }

    fun onSelectAnotherBank() {
        navigationManager.tryNavigateTo(Reset(referrer = PANE))
    }

    fun onWebAuthFlowFinished(
        webStatus: WebAuthFlowState
    ) {
        logger.debug("Web AuthFlow status received $webStatus")
        viewModelScope.launch {
            when (webStatus) {
                is WebAuthFlowState.Canceled -> onAuthCancelled(webStatus.url)
                is WebAuthFlowState.Failed -> onAuthFailed(
                    url = webStatus.url,
                    message = webStatus.message,
                    reason = webStatus.reason
                )

                WebAuthFlowState.InProgress -> setState { copy(authenticationStatus = Loading()) }
                is WebAuthFlowState.Success -> completeRepairSession(webStatus.url)
                WebAuthFlowState.Uninitialized -> {}
            }
        }
    }

    private suspend fun onAuthFailed(
        url: String,
        message: String,
        reason: String?
    ) {
        eventTracker.logError(
            extraMessage = "Repair auth session failed. url: $url",
            error = WebAuthFlowFailedException(message, reason),
            logger = logger,
            pane = PANE
        )
        navigationManager.tryNavigateTo(LinkAccountPicker(referrer = PANE))
    }

    private fun onAuthCancelled(url: String?) {
        logger.debug("Auth cancelled $url")
        navigationManager.tryNavigateTo(LinkAccountPicker(referrer = PANE))
    }

    private suspend fun completeRepairSession(url: String) = runCatching {
        val repairPayload = requireNotNull(awaitState().payload()?.repairPayload)
        val repairSession = requireNotNull(awaitState().payload()?.authSession)
        completeRepairSession(
            authRepairSessionId = repairSession.id,
            coreAuthorization = repairPayload.coreAuthorization
        )

        val activeInstitution = selectNetworkedAccount(
            consumerSessionClientSecret = repairPayload.consumerSession,
            selectedAccountId = repairPayload.selectedAccountId,
        )
        // Updates manifest active institution after account networked.
        updateLocalManifest { it.copy(activeInstitution = activeInstitution.data.firstOrNull()) }
        saveToLinkWithStripeSucceeded.set(true)
        navigationManager.tryNavigateTo(Destination.Success(referrer = PANE))
    }.onFailure {
        eventTracker.logError(
            extraMessage = "failed networking repaired account. url: $url",
            error = it,
            logger = logger,
            pane = PANE
        )
        setState { copy(authenticationStatus = Fail(it)) }
    }

    fun onEnterDetailsManuallyClick() {
        navigationManager.tryNavigateTo(ManualEntry(referrer = PANE))
    }

    fun onClickableTextClick(uri: String) = viewModelScope.launch {
        // if clicked uri contains an eventName query param, track click event.
        viewModelScope.launch {
            uriUtils.getQueryParameter(uri, "eventName")?.let { eventName ->
                eventTracker.track(Click(eventName, pane = PANE))
            }
        }
        if (URLUtil.isNetworkUrl(uri)) {
            setState {
                copy(
                    viewEffect = OpenUrl(uri, Date().time)
                )
            }
        } else {
            val managedUri = ClickableText.values()
                .firstOrNull { uriUtils.compareSchemeAuthorityAndPath(it.value, uri) }
            when (managedUri) {
                ClickableText.DATA -> {
                    setState {
                        copy(
                            viewEffect = OpenBottomSheet(Date().time)
                        )
                    }
                }

                null -> logger.error("Unrecognized clickable text: $uri")
            }
        }
    }

    fun onViewEffectLaunched() {
        setState {
            copy(viewEffect = null)
        }
    }

    internal companion object :
        MavericksViewModelFactory<BankAuthRepairViewModel, PartnerAuthState> {

        override fun create(
            viewModelContext: ViewModelContext,
            state: PartnerAuthState
        ): BankAuthRepairViewModel {
            return viewModelContext.activity<FinancialConnectionsSheetNativeActivity>()
                .viewModel
                .activityRetainedComponent
                .bankAuthRepairSubcomponent
                .initialState(state)
                .build()
                .viewModel
        }

        val PANE = Pane.BANK_AUTH_REPAIR
    }
}
