package com.stripe.android.repository

import com.google.common.truth.Truth.assertThat
import com.stripe.android.ConsumerFixtures
import com.stripe.android.core.ApiVersion
import com.stripe.android.core.exception.APIException
import com.stripe.android.core.networking.ApiRequest
import com.stripe.android.core.networking.DefaultStripeNetworkClient
import com.stripe.android.core.utils.urlEncode
import com.stripe.android.core.version.StripeSdkVersion
import com.stripe.android.model.ConsumerSession
import com.stripe.android.model.ConsumerSignUpConsentAction
import com.stripe.android.model.VerificationType
import com.stripe.android.networktesting.NetworkRule
import com.stripe.android.networktesting.RequestMatchers.bodyPart
import com.stripe.android.networktesting.RequestMatchers.header
import com.stripe.android.networktesting.RequestMatchers.method
import com.stripe.android.networktesting.RequestMatchers.path
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import kotlin.test.assertFailsWith

class ConsumersApiServiceImplTest {

    @get:Rule
    val networkRule = NetworkRule()

    private val consumersApiService = ConsumersApiServiceImpl(
        stripeNetworkClient = DefaultStripeNetworkClient(),
        sdkVersion = StripeSdkVersion.VERSION,
        apiVersion = ApiVersion(betas = emptySet()).code,
        appInfo = null
    )

    @Test
    fun `signUp() sends all parameters`() = runTest {
        val email = "email@example.com"
        val requestSurface = "android_payment_element"

        networkRule.enqueue(
            method("POST"),
            path("/v1/consumers/accounts/sign_up"),
            header("Authorization", "Bearer ${DEFAULT_OPTIONS.apiKey}"),
            header("User-Agent", "Stripe/v1 ${StripeSdkVersion.VERSION}"),
            bodyPart("email_address", "email%40example.com"),
            bodyPart("phone_number", "%2B15555555568"),
            bodyPart("country", "US"),
            bodyPart("locale", "en-US"),
            bodyPart("consent_action", "clicked_checkbox_nospm_mobile_v0"),
            bodyPart("request_surface", requestSurface),
        ) { response ->
            response.setBody(ConsumerFixtures.EXISTING_CONSUMER_JSON.toString())
        }

        val signup = consumersApiService.signUp(
            email = email,
            phoneNumber = "+15555555568",
            country = "US",
            name = null,
            locale = Locale.US,
            consentAction = ConsumerSignUpConsentAction.Checkbox,
            requestSurface = requestSurface,
            requestOptions = DEFAULT_OPTIONS,
        )

        assertThat(signup.consumerSession.verificationSessions).isEmpty()
        assertThat(signup.consumerSession.emailAddress).isEqualTo(email)
        assertThat(signup.consumerSession.redactedPhoneNumber).isEqualTo("+1********68")
        assertThat(signup.consumerSession.clientSecret).isEqualTo("secret")
        assertThat(signup.publishableKey).isEqualTo("asdfg123")
    }

    @Test
    fun `lookupConsumerSession() sends all parameters`() = runTest {
        val email = "email@example.com"
        val requestSurface = "android_payment_element"

        networkRule.enqueue(
            method("POST"),
            path("/v1/consumers/sessions/lookup"),
            header("Authorization", "Bearer ${DEFAULT_OPTIONS.apiKey}"),
            header("User-Agent", "Stripe/v1 ${StripeSdkVersion.VERSION}"),
            bodyPart("email_address", "email%40example.com"),
            bodyPart("request_surface", requestSurface),
        ) { response ->
            response.setBody(ConsumerFixtures.EXISTING_CONSUMER_JSON.toString())
        }

        val lookup = consumersApiService.lookupConsumerSession(
            email = email,
            requestSurface = requestSurface,
            requestOptions = DEFAULT_OPTIONS
        )

        assertThat(lookup.exists).isTrue()
        assertThat(lookup.errorMessage).isNull()
        assertThat(lookup.consumerSession?.verificationSessions).isEmpty()
        assertThat(lookup.consumerSession?.emailAddress).isEqualTo(email)
        assertThat(lookup.consumerSession?.redactedPhoneNumber).isEqualTo("+1********68")
        assertThat(lookup.consumerSession?.clientSecret).isEqualTo("secret")
    }

    @Test
    fun `lookupConsumerSession() returns error message with invalid json`() = runTest {
        val email = "email@example.com"
        val requestSurface = "android_payment_element"

        networkRule.enqueue(
            method("POST"),
            path("/v1/consumers/sessions/lookup"),
        ) { response ->
            response.setResponseCode(400)
            response.setBody("""{"invalid-json"}""")
        }

        assertFailsWith<APIException> {
            consumersApiService.lookupConsumerSession(
                email = email,
                requestSurface = requestSurface,
                requestOptions = DEFAULT_OPTIONS
            )
        }
    }

    @Test
    fun `startConsumerVerification() sends all parameters`() = runTest {
        val clientSecret = "secret"
        val locale = Locale.US

        networkRule.enqueue(
            method("POST"),
            path("/v1/consumers/sessions/start_verification"),
            header("Authorization", "Bearer ${DEFAULT_OPTIONS.apiKey}"),
            header("User-Agent", "Stripe/v1 ${StripeSdkVersion.VERSION}"),
            bodyPart("request_surface", "android_payment_element"),
            bodyPart(urlEncode("credentials[consumer_session_client_secret]"), clientSecret),
            bodyPart("type", "SMS"),
            bodyPart("locale", locale.toLanguageTag()),
        ) { response ->
            response.setBody(ConsumerFixtures.CONSUMER_VERIFICATION_STARTED_JSON.toString())
        }

        val consumerSession = consumersApiService.startConsumerVerification(
            consumerSessionClientSecret = clientSecret,
            locale = locale,
            requestSurface = "android_payment_element",
            type = VerificationType.SMS,
            customEmailType = null,
            connectionsMerchantName = null,
            requestOptions = DEFAULT_OPTIONS
        )

        assertThat(consumerSession.redactedPhoneNumber).isEqualTo("+1********56")
        assertThat(consumerSession.verificationSessions).contains(
            ConsumerSession.VerificationSession(
                ConsumerSession.VerificationSession.SessionType.Sms,
                ConsumerSession.VerificationSession.SessionState.Started
            )
        )
    }

    @Test
    fun `confirmConsumerVerification() sends all parameters`() = runTest {
        val clientSecret = "secret"
        val verificationCode = "1234"

        networkRule.enqueue(
            method("POST"),
            path("/v1/consumers/sessions/confirm_verification"),
            header("Authorization", "Bearer ${DEFAULT_OPTIONS.apiKey}"),
            header("User-Agent", "Stripe/v1 ${StripeSdkVersion.VERSION}"),
            bodyPart("request_surface", "android_payment_element"),
            bodyPart(urlEncode("credentials[consumer_session_client_secret]"), clientSecret),
            bodyPart("type", "SMS"),
            bodyPart("code", verificationCode),
        ) { response ->
            response.setBody(ConsumerFixtures.CONSUMER_VERIFIED_JSON.toString())
        }

        val consumerSession = consumersApiService.confirmConsumerVerification(
            consumerSessionClientSecret = clientSecret,
            verificationCode = verificationCode,
            requestSurface = "android_payment_element",
            type = VerificationType.SMS,
            requestOptions = DEFAULT_OPTIONS
        )

        assertThat(consumerSession.redactedPhoneNumber).isEqualTo("+1********56")
        assertThat(consumerSession.verificationSessions).contains(
            ConsumerSession.VerificationSession(
                ConsumerSession.VerificationSession.SessionType.Sms,
                ConsumerSession.VerificationSession.SessionState.Verified
            )
        )
    }

    @Test
    fun testConsumerSessionLookupUrl() {
        assertThat("https://api.stripe.com/v1/consumers/sessions/lookup")
            .isEqualTo(ConsumersApiServiceImpl.consumerSessionLookupUrl)
    }

    @Test
    fun testStartConsumerVerificationUrl() {
        assertThat("https://api.stripe.com/v1/consumers/sessions/start_verification")
            .isEqualTo(ConsumersApiServiceImpl.startConsumerVerificationUrl)
    }

    @Test
    fun testConfirmConsumerVerificationUrl() {
        assertThat("https://api.stripe.com/v1/consumers/sessions/confirm_verification")
            .isEqualTo(ConsumersApiServiceImpl.confirmConsumerVerificationUrl)
    }

    private companion object {
        private val DEFAULT_OPTIONS = ApiRequest.Options("pk_test_vOo1umqsYxSrP5UXfOeL3ecm")
    }
}
