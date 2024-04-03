package com.stripe.android.link.repositories

import com.stripe.android.core.injection.IOContext
import com.stripe.android.core.injection.PUBLISHABLE_KEY
import com.stripe.android.core.injection.STRIPE_ACCOUNT_ID
import com.stripe.android.core.networking.ApiRequest
import com.stripe.android.link.LinkPaymentDetails
import com.stripe.android.link.injection.LinkScope
import com.stripe.android.model.ConsumerPaymentDetails
import com.stripe.android.model.ConsumerPaymentDetailsCreateParams
import com.stripe.android.model.ConsumerPaymentDetailsCreateParams.Card.Companion.extraConfirmationParams
import com.stripe.android.model.ConsumerSession
import com.stripe.android.model.ConsumerSessionLookup
import com.stripe.android.model.ConsumerSignUpConsentAction
import com.stripe.android.model.PaymentMethodCreateParams
import com.stripe.android.model.StripeIntent
import com.stripe.android.networking.StripeRepository
import com.stripe.android.repository.ConsumersApiService
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Named
import kotlin.coroutines.CoroutineContext

/**
 * Repository that uses [StripeRepository] for Link services.
 */
@LinkScope
internal class LinkApiRepository @Inject constructor(
    @Named(PUBLISHABLE_KEY) private val publishableKeyProvider: () -> String,
    @Named(STRIPE_ACCOUNT_ID) private val stripeAccountIdProvider: () -> String?,
    private val stripeRepository: StripeRepository,
    private val consumersApiService: ConsumersApiService,
    @IOContext private val workContext: CoroutineContext,
    private val locale: Locale?
) : LinkRepository {

    override suspend fun lookupConsumer(
        email: String?,
        authSessionCookie: String?
    ): Result<ConsumerSessionLookup> = withContext(workContext) {
        runCatching {
            requireNotNull(
                consumersApiService.lookupConsumerSession(
                    email = email,
                    authSessionCookie = authSessionCookie,
                    requestSurface = REQUEST_SURFACE,
                    requestOptions = buildRequestOptions(),
                )
            )
        }
    }

    override suspend fun consumerSignUp(
        email: String,
        phone: String,
        country: String,
        name: String?,
        authSessionCookie: String?,
        consentAction: ConsumerSignUpConsentAction
    ): Result<ConsumerSession> = withContext(workContext) {
        stripeRepository.consumerSignUp(
            email = email,
            phoneNumber = phone,
            country = country,
            name = name,
            locale = locale,
            authSessionCookie = authSessionCookie,
            consentAction = consentAction,
            requestOptions = buildRequestOptions(),
        )
    }

    override suspend fun createCardPaymentDetails(
        paymentMethodCreateParams: PaymentMethodCreateParams,
        userEmail: String,
        stripeIntent: StripeIntent,
        consumerSessionClientSecret: String,
        consumerPublishableKey: String?,
        active: Boolean,
    ): Result<LinkPaymentDetails.New> = withContext(workContext) {
        // Add event here?
        stripeRepository.createPaymentDetails(
            consumerSessionClientSecret = consumerSessionClientSecret,
            paymentDetailsCreateParams = ConsumerPaymentDetailsCreateParams.Card(
                cardPaymentMethodCreateParamsMap = paymentMethodCreateParams.toParamMap(),
                email = userEmail,
            ),
            active = active,
            requestOptions = buildRequestOptions(consumerPublishableKey),
        ).mapCatching {
            val paymentDetails = it.paymentDetails.first()
            val extraParams = extraConfirmationParams(paymentMethodCreateParams)

            val createParams = PaymentMethodCreateParams.createLink(
                paymentDetailsId = paymentDetails.id,
                consumerSessionClientSecret = consumerSessionClientSecret,
                extraParams = extraParams,
            )

            LinkPaymentDetails.New(
                paymentDetails = paymentDetails,
                paymentMethodCreateParams = createParams,
                originalParams = paymentMethodCreateParams,
            )
        }
    }

    override suspend fun shareCardPaymentDetails(
        paymentMethodCreateParams: PaymentMethodCreateParams,
        id: String,
        last4: String,
        consumerSessionClientSecret: String,
    ): Result<LinkPaymentDetails> = withContext(workContext) {
        // Add event here?
        stripeRepository.sharePaymentDetails(
            consumerSessionClientSecret = consumerSessionClientSecret,
            id = id,
            requestOptions = buildRequestOptions(),
        ).mapCatching { passthroughModePaymentMethodId ->
            LinkPaymentDetails.Saved(
                paymentDetails = ConsumerPaymentDetails.Passthrough(
                    id = passthroughModePaymentMethodId,
                    last4 = last4,
                ),
                paymentMethodCreateParams = PaymentMethodCreateParams.createLink(
                    paymentDetailsId = passthroughModePaymentMethodId,
                    consumerSessionClientSecret = consumerSessionClientSecret,
                    extraParams = extraConfirmationParams(paymentMethodCreateParams)
                ),
            )
        }
    }

    override suspend fun logOut(
        consumerSessionClientSecret: String,
        consumerAccountPublishableKey: String?,
    ): Result<ConsumerSession> = withContext(workContext) {
        // Add event here?
        stripeRepository.logOut(
            consumerSessionClientSecret = consumerSessionClientSecret,
            consumerAccountPublishableKey = consumerAccountPublishableKey,
            requestOptions = buildRequestOptions(consumerAccountPublishableKey),
        )
    }

    private fun buildRequestOptions(
        consumerAccountPublishableKey: String? = null,
    ): ApiRequest.Options {
        return ApiRequest.Options(
            apiKey = consumerAccountPublishableKey ?: publishableKeyProvider(),
            stripeAccount = stripeAccountIdProvider().takeUnless { consumerAccountPublishableKey != null },
        )
    }

    private companion object {
        const val REQUEST_SURFACE = "android_payment_element"
    }
}
