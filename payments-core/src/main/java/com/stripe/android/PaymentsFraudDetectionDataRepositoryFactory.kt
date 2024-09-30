package com.stripe.android

import android.content.Context
import com.stripe.android.core.networking.DefaultStripeNetworkClient
import com.stripe.android.networking.DefaultFraudDetectionDataRequestFactory
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
        )
    }
}
