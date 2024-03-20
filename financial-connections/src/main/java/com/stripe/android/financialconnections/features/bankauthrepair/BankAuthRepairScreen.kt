package com.stripe.android.financialconnections.features.bankauthrepair

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import com.airbnb.mvrx.compose.collectAsState
import com.airbnb.mvrx.compose.mavericksViewModel
import com.stripe.android.financialconnections.features.common.SharedPartnerAuth
import com.stripe.android.financialconnections.features.partnerauth.SharedPartnerAuthState
import com.stripe.android.financialconnections.model.FinancialConnectionsSessionManifest.Pane

@Composable
internal fun BankAuthRepairScreen() {
    // step view model
    val viewModel: BankAuthRepairViewModel = mavericksViewModel(
        argsFactory = { BankAuthRepairViewModel.Args(Pane.BANK_AUTH_REPAIR) },
    )
    val state: State<SharedPartnerAuthState> = viewModel.collectAsState()

    SharedPartnerAuth(
        state = state.value,
        onContinueClick = { /*TODO*/ },
        onCancelClick = { /*TODO*/ },
        onClickableTextClick = { /*TODO*/ },
        onWebAuthFlowFinished = { /*TODO*/ },
        onViewEffectLaunched = { /*TODO*/ },
        inModal = false
    )
}
