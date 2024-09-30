package com.stripe.android

import android.content.Context
import com.stripe.android.core.frauddetection.DefaultFraudDetectionDataRepository
import com.stripe.android.core.frauddetection.DefaultFraudDetectionDataRequestFactory
import com.stripe.android.core.frauddetection.DefaultFraudDetectionDataStore
import com.stripe.android.core.frauddetection.FraudDetectionDataRepository
import com.stripe.android.core.networking.DefaultStripeNetworkClient
import com.stripe.android.payments.core.analytics.ErrorReporter
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

internal object PaymentsFraudDetectionDataRepositoryFactory {

    @JvmOverloads
    fun create(
        context: Context,
        workContext: CoroutineContext = Dispatchers.IO,
    ): FraudDetectionDataRepository {
        return DefaultFraudDetectionDataRepository(
            localStore = DefaultFraudDetectionDataStore(context, workContext),
            fraudDetectionDataRequestFactory = DefaultFraudDetectionDataRequestFactory(context),
            stripeNetworkClient = DefaultStripeNetworkClient(workContext = workContext),
            errorReporter = ErrorReporter.createFallbackInstance(context, emptySet()),
            workContext = workContext,
            fraudDetectionEnabledProvider = { Stripe.advancedFraudSignalsEnabled },
        )
    }
}
