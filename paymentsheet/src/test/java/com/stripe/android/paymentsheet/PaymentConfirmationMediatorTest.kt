package com.stripe.android.paymentsheet

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.stripe.android.core.strings.resolvableString
import com.stripe.android.model.PaymentIntentFixtures
import com.stripe.android.model.PaymentMethodFixtures
import com.stripe.android.paymentsheet.utils.FakePaymentConfirmationDefinition
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock

class PaymentConfirmationMediatorTest {
    @Test
    fun `On incorrect confirmation option provided on action, should return fail action`() = runTest {
        val mediator = PaymentConfirmationMediator(
            savedStateHandle = SavedStateHandle(),
            definition = FakePaymentConfirmationDefinition()
        )

        val action = mediator.action(
            option = PaymentConfirmationOption.ExternalPaymentMethod(
                type = "paypal",
                billingDetails = null,
            ),
            intent = PaymentIntentFixtures.PI_SUCCEEDED,
        )

        val failAction = action.asFail()

        assertThat(failAction.cause).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failAction.cause.message).isEqualTo(
            "Parameter type of 'ExternalPaymentMethod' cannot be used with " +
                "PaymentConfirmationMediator to read a result"
        )
        assertThat(failAction.message).isEqualTo(R.string.stripe_something_went_wrong.resolvableString)
        assertThat(failAction.errorType).isEqualTo(PaymentConfirmationErrorType.Internal)
    }

    @Test
    fun `On complete confirmation action, should return mediator complete action`() = runTest {
        val definition = FakePaymentConfirmationDefinition(
            onAction = { confirmationOption, intent ->
                PaymentConfirmationDefinition.ConfirmationAction.Complete(
                    confirmationOption = confirmationOption,
                    intent = intent,
                    deferredIntentConfirmationType = DeferredIntentConfirmationType.Client,
                )
            }
        )

        val mediator = PaymentConfirmationMediator(
            savedStateHandle = SavedStateHandle(),
            definition = definition
        )

        val action = mediator.action(
            option = SAVED_CONFIRMATION_OPTION,
            intent = INTENT,
        )

        val completeAction = action.asComplete()

        assertThat(completeAction.confirmationOption).isEqualTo(SAVED_CONFIRMATION_OPTION)
        assertThat(completeAction.intent).isEqualTo(INTENT)
        assertThat(completeAction.deferredIntentConfirmationType).isEqualTo(DeferredIntentConfirmationType.Client)
    }

    @Test
    fun `On failed confirmation action, should return mediator fail action`() = runTest {
        val exception = IllegalStateException("Failed!")
        val message = R.string.stripe_something_went_wrong.resolvableString
        val errorType = PaymentConfirmationErrorType.Fatal

        val definition = FakePaymentConfirmationDefinition(
            onAction = { _, _ ->
                PaymentConfirmationDefinition.ConfirmationAction.Fail(
                    cause = exception,
                    message = R.string.stripe_something_went_wrong.resolvableString,
                    errorType = errorType,
                )
            }
        )

        val mediator = PaymentConfirmationMediator(
            savedStateHandle = SavedStateHandle(),
            definition = definition
        )

        val action = mediator.action(
            option = SAVED_CONFIRMATION_OPTION,
            intent = INTENT,
        )

        val failAction = action.asFail()

        assertThat(failAction.cause).isInstanceOf(IllegalStateException::class.java)
        assertThat(failAction.cause.message).isEqualTo("Failed!")
        assertThat(failAction.message).isEqualTo(message)
        assertThat(failAction.errorType).isEqualTo(errorType)
    }

    @Test
    fun `On launch action, should call definition launch and persist parameters`() = runTest {
        val launcherArguments = FakePaymentConfirmationDefinition.LauncherArgs(amount = 5000)
        val launcher = FakePaymentConfirmationDefinition.Launcher()

        val definition = FakePaymentConfirmationDefinition(
            onAction = { _, _ ->
                PaymentConfirmationDefinition.ConfirmationAction.Launch(
                    launcherArguments = launcherArguments,
                    deferredIntentConfirmationType = DeferredIntentConfirmationType.Client,
                )
            },
            launcher = launcher,
        )

        val savedStateHandle = SavedStateHandle()

        val mediator = PaymentConfirmationMediator(
            savedStateHandle = savedStateHandle,
            definition = definition
        ).apply {
            register(
                activityResultCaller = mock(),
                onResult = {}
            )
        }

        val action = mediator.action(
            option = SAVED_CONFIRMATION_OPTION,
            intent = INTENT,
        )

        val launchAction = action.asLaunch()

        launchAction.launch()

        val launchCall = definition.launchCalls.awaitItem()

        assertThat(launchCall.confirmationOption).isEqualTo(SAVED_CONFIRMATION_OPTION)
        assertThat(launchCall.arguments).isEqualTo(launcherArguments)
        assertThat(launchCall.intent).isEqualTo(INTENT)
        assertThat(launchCall.launcher).isEqualTo(launcher)

        val parameters = savedStateHandle
            .get<PaymentConfirmationMediator.Parameters<PaymentConfirmationOption.PaymentMethod.Saved>>(
                "TestParameters"
            )

        assertThat(parameters?.confirmationOption).isEqualTo(SAVED_CONFIRMATION_OPTION)
        assertThat(parameters?.intent).isEqualTo(INTENT)
        assertThat(parameters?.deferredIntentConfirmationType).isEqualTo(DeferredIntentConfirmationType.Client)
    }

    @Test
    fun `On launch confirmation action without registering, should return fail action`() = runTest {
        val definition = FakePaymentConfirmationDefinition(
            onAction = { _, _ ->
                PaymentConfirmationDefinition.ConfirmationAction.Launch(
                    launcherArguments = FakePaymentConfirmationDefinition.LauncherArgs(amount = 5000),
                    deferredIntentConfirmationType = null,
                )
            },
        )

        val mediator = PaymentConfirmationMediator(
            savedStateHandle = SavedStateHandle(),
            definition = definition
        )

        val action = mediator.action(
            option = SAVED_CONFIRMATION_OPTION,
            intent = INTENT,
        )

        val failAction = action.asFail()

        assertThat(failAction.cause).isInstanceOf(IllegalStateException::class.java)
        assertThat(failAction.cause.message).isEqualTo(
            "No launcher for FakePaymentConfirmationDefinition was found, did you call register?"
        )
        assertThat(failAction.message).isEqualTo(R.string.stripe_something_went_wrong.resolvableString)
        assertThat(failAction.errorType).isEqualTo(PaymentConfirmationErrorType.Fatal)
    }

    @Test
    fun `On result with no persisted parameters, should return failed result`() = runTest {
        val definition = FakePaymentConfirmationDefinition(
            onAction = { _, _ ->
                PaymentConfirmationDefinition.ConfirmationAction.Launch(
                    launcherArguments = FakePaymentConfirmationDefinition.LauncherArgs(amount = 5000),
                    deferredIntentConfirmationType = null,
                )
            },
        )

        val mediator = PaymentConfirmationMediator(
            savedStateHandle = SavedStateHandle(),
            definition = definition
        )

        val action = mediator.action(
            option = SAVED_CONFIRMATION_OPTION,
            intent = INTENT,
        )

        val failAction = action.asFail()

        assertThat(failAction.cause).isInstanceOf(IllegalStateException::class.java)
        assertThat(failAction.cause.message).isEqualTo(
            "No launcher for FakePaymentConfirmationDefinition was found, did you call register?"
        )
        assertThat(failAction.message).isEqualTo(R.string.stripe_something_went_wrong.resolvableString)
        assertThat(failAction.errorType).isEqualTo(PaymentConfirmationErrorType.Fatal)
    }

    private fun PaymentConfirmationMediator.Action.asFail(): PaymentConfirmationMediator.Action.Fail {
        return this as PaymentConfirmationMediator.Action.Fail
    }

    private fun PaymentConfirmationMediator.Action.asComplete(): PaymentConfirmationMediator.Action.Complete {
        return this as PaymentConfirmationMediator.Action.Complete
    }

    private fun PaymentConfirmationMediator.Action.asLaunch(): PaymentConfirmationMediator.Action.Launch {
        return this as PaymentConfirmationMediator.Action.Launch
    }

    private companion object {
        private val SAVED_CONFIRMATION_OPTION = PaymentConfirmationOption.PaymentMethod.Saved(
            initializationMode = PaymentSheet.InitializationMode.PaymentIntent(clientSecret = "pi_123"),
            shippingDetails = null,
            paymentMethod = PaymentMethodFixtures.CARD_PAYMENT_METHOD,
            optionsParams = null,
        )

        private val INTENT = PaymentIntentFixtures.PI_REQUIRES_PAYMENT_METHOD
    }
}
