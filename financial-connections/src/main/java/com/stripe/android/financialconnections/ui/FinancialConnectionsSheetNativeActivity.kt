package com.stripe.android.financialconnections.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.ModalBottomSheetLayout
import androidx.compose.material.ModalBottomSheetValue.Hidden
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.airbnb.mvrx.MavericksView
import com.airbnb.mvrx.compose.collectAsState
import com.airbnb.mvrx.withState
import com.stripe.android.core.Logger
import com.stripe.android.financialconnections.browser.BrowserManager
import com.stripe.android.financialconnections.features.common.ExitModal
import com.stripe.android.financialconnections.launcher.FinancialConnectionsSheetNativeActivityArgs
import com.stripe.android.financialconnections.model.FinancialConnectionsSessionManifest.Pane
import com.stripe.android.financialconnections.navigation.Destination
import com.stripe.android.financialconnections.navigation.NavigationIntent
import com.stripe.android.financialconnections.navigation.composable
import com.stripe.android.financialconnections.navigation.destination
import com.stripe.android.financialconnections.navigation.pane
import com.stripe.android.financialconnections.presentation.FinancialConnectionsSheetNativeViewEffect.Finish
import com.stripe.android.financialconnections.presentation.FinancialConnectionsSheetNativeViewEffect.OpenUrl
import com.stripe.android.financialconnections.presentation.FinancialConnectionsSheetNativeViewModel
import com.stripe.android.financialconnections.ui.theme.FinancialConnectionsTheme
import com.stripe.android.financialconnections.ui.theme.Neutral900
import com.stripe.android.financialconnections.utils.argsOrNull
import com.stripe.android.financialconnections.utils.viewModelLazy
import com.stripe.android.uicore.image.StripeImageLoader
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

internal class FinancialConnectionsSheetNativeActivity : AppCompatActivity(), MavericksView {

    val args by argsOrNull<FinancialConnectionsSheetNativeActivityArgs>()

    val viewModel: FinancialConnectionsSheetNativeViewModel by viewModelLazy()

    @Inject
    lateinit var logger: Logger

    @Inject
    lateinit var imageLoader: StripeImageLoader

    @Inject
    lateinit var browserManager: BrowserManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (args == null) {
            finish()
        } else {
            viewModel.activityRetainedComponent.inject(this)
            viewModel.onEach { postInvalidate() }
            onBackPressedDispatcher.addCallback { viewModel.onBackPressed() }
            setContent {
                FinancialConnectionsTheme {
                    val firstPane by viewModel.collectAsState { it.initialPane }
                    val reducedBranding by viewModel.collectAsState { it.reducedBranding }
                    NavHost(
                        initialPane = firstPane,
                        reducedBranding = reducedBranding
                    )
                }
            }
        }
    }

    /**
     * handle state changes here.
     */
    override fun invalidate() {
        withState(viewModel) { state ->
            state.viewEffect?.let { viewEffect ->
                when (viewEffect) {
                    is OpenUrl -> startActivity(
                        browserManager.createBrowserIntentForUrl(uri = Uri.parse(viewEffect.url))
                    )

                    is Finish -> {
                        setResult(
                            Activity.RESULT_OK,
                            Intent().putExtra(EXTRA_RESULT, viewEffect.result)
                        )
                        finish()
                    }
                }
                viewModel.onViewEffectLaunched()
            }
        }
    }

    @OptIn(ExperimentalMaterialApi::class)
    @Suppress("LongMethod")
    @Composable
    fun NavHost(
        initialPane: Pane,
        reducedBranding: Boolean
    ) {
        val context = LocalContext.current
        val navController = rememberNavController()
        val coroutineScope = rememberCoroutineScope()
        val uriHandler = remember { CustomTabUriHandler(context, browserManager) }
        val initialDestination = remember(initialPane) { initialPane.destination }
        val bottomState = rememberModalBottomSheetState(initialValue = Hidden)
        val exitModal by viewModel.collectAsState { it.exitModal }

        PaneBackgroundEffects(navController)
        NavigationEffects(viewModel.navigationFlow, navController)
        // Show the exit modal when it is not null
        LaunchedEffect(exitModal) { if (exitModal != null) bottomState.show() }
        // Notify modal dismissal when the bottom state is hidden
        LaunchedEffect(bottomState.currentValue) { if (bottomState.currentValue == Hidden) viewModel.onCloseDismiss() }

        CompositionLocalProvider(
            LocalReducedBranding provides reducedBranding,
            LocalNavHostController provides navController,
            LocalImageLoader provides imageLoader,
            LocalUriHandler provides uriHandler
        ) {
            BackHandler(true) {
                viewModel.onBackClick(navController.currentDestination?.pane)
                if (navController.popBackStack().not()) viewModel.onBackPressed()
            }
            ModalBottomSheetLayout(
                sheetState = bottomState,
                sheetBackgroundColor = FinancialConnectionsTheme.v3Colors.backgroundSurface,
                sheetShape = RoundedCornerShape(8.dp),
                scrimColor = Neutral900.copy(alpha = 0.32f),
                sheetContent = {
                    exitModal?.let {
                        ExitModal(
                            loading = it.loading,
                            description = it.description,
                            onCancel = { coroutineScope.launch { bottomState.hide() } },
                            onExit = viewModel::onCloseConfirm
                        )
                    }
                },
                content = {
                    NavHost(
                        navController,
                        startDestination = initialDestination.fullRoute,
                    ) {
                        composable(Destination.Consent)
                        composable(Destination.ManualEntry)
                        composable(Destination.PartnerAuth)
                        composable(Destination.InstitutionPicker)
                        composable(Destination.AccountPicker)
                        composable(Destination.Success)
                        composable(Destination.Reset)
                        composable(Destination.AttachLinkedPaymentAccount)
                        composable(Destination.NetworkingLinkSignup)
                        composable(Destination.NetworkingLinkLoginWarmup)
                        composable(Destination.NetworkingLinkVerification)
                        composable(Destination.NetworkingSaveToLinkVerification)
                        composable(Destination.LinkAccountPicker)
                        composable(Destination.BankAuthRepair)
                        composable(Destination.LinkStepUpVerification)
                        composable(Destination.ManualEntrySuccess)
                    }
                }
            )
        }
    }

    @Composable
    private fun PaneBackgroundEffects(
        navController: NavHostController
    ) {
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val lifecycle = lifecycleOwner.lifecycle
            val observer = ActivityVisibilityObserver(
                onBackgrounded = {
                    viewModel.onBackgrounded(navController.currentDestination, true)
                },
                onForegrounded = {
                    viewModel.onBackgrounded(navController.currentDestination, false)
                }
            )
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
    }

    /**
     * Handles new intents in the form of the redirect from the custom tab hosted auth flow
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        viewModel.handleOnNewIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    @Composable
    fun NavigationEffects(
        navigationChannel: SharedFlow<NavigationIntent>,
        navHostController: NavHostController
    ) {
        val activity = (LocalContext.current as? Activity)
        LaunchedEffect(activity, navHostController, navigationChannel) {
            navigationChannel.onEach { intent ->
                if (activity?.isFinishing == true) {
                    return@onEach
                }
                when (intent) {
                    is NavigationIntent.NavigateTo -> {
                        val from: String? = navHostController.currentDestination?.route
                        val destination: String = intent.route
                        if (destination.isNotEmpty() && destination != from) {
                            logger.debug("Navigating from $from to $destination")
                            navHostController.navigate(destination) {
                                launchSingleTop = intent.isSingleTop
                                if (from != null && intent.popUpToCurrent) {
                                    popUpTo(from) { inclusive = true }
                                }
                            }
                        }
                    }
                }
            }.launchIn(this)
        }
    }

    internal companion object {
        internal const val EXTRA_RESULT = "result"
    }
}

internal val LocalNavHostController = staticCompositionLocalOf<NavHostController> {
    error("No NavHostController provided")
}

internal val LocalReducedBranding = staticCompositionLocalOf<Boolean> {
    error("No ReducedBranding provided")
}

internal val LocalImageLoader = staticCompositionLocalOf<StripeImageLoader> {
    error("No ImageLoader provided")
}

/**
 * Observer that will notify the view model when the activity is moved to the background or
 * brought back to the foreground.
 */
private class ActivityVisibilityObserver(
    val onBackgrounded: () -> Unit,
    val onForegrounded: () -> Unit
) : DefaultLifecycleObserver {

    private var isFirstStart = true
    private var isInBackground = false

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        if (!isFirstStart && isInBackground) {
            onForegrounded()
        }
        isFirstStart = false
        isInBackground = false
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        // If the activity is being rotated, we don't want to notify a backgrounded state
        val changingConfigurations =
            (owner as? AppCompatActivity)?.isChangingConfigurations ?: false
        if (!changingConfigurations) {
            isInBackground = true
            onBackgrounded()
        }
    }
}
