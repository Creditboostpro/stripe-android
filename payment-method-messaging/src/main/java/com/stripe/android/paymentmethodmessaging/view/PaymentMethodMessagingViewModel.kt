package com.stripe.android.paymentmethodmessaging.view

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.stripe.android.core.exception.StripeException
import com.stripe.android.core.networking.ApiRequest
import com.stripe.android.model.PaymentMethodMessage
import com.stripe.android.networking.StripeApiRepository
import com.stripe.android.paymentmethodmessaging.view.injection.DaggerPaymentMethodMessagingComponent
import com.stripe.android.utils.requireApplication
import javax.inject.Inject

internal class PaymentMethodMessagingViewModel @Inject constructor(
    private val isSystemDarkTheme: Boolean,
    private val configuration: PaymentMethodMessagingView.Configuration,
    private val stripeApiRepository: StripeApiRepository
) : ViewModel() {
    suspend fun loadMessage(): Result<PaymentMethodMessage> {
        return try {
            val message = stripeApiRepository.retrievePaymentMethodMessage(
                paymentMethods = configuration.paymentMethods.map { it.value },
                amount = configuration.amount,
                currency = configuration.currency,
                country = configuration.countryCode,
                locale = configuration.locale.toLanguageTag(),
                logoColor = configuration.imageColor?.value ?: if (isSystemDarkTheme) {
                    PaymentMethodMessagingView.Configuration.ImageColor.Light.value
                } else {
                    PaymentMethodMessagingView.Configuration.ImageColor.Dark.value
                },
                requestOptions = ApiRequest.Options(configuration.publishableKey),
            )
            if (
                message == null ||
                message.displayHtml.isBlank() ||
                message.learnMoreUrl.isBlank()
            ) {
                Result.failure(Exception("Could not retrieve message"))
            } else {
                Result.success(message)
            }
        } catch (e: StripeException) {
            Result.failure(e)
        }
    }

    internal class Factory(
        private val configurationProvider: () -> PaymentMethodMessagingView.Configuration,
        private val isSystemDarkThemeProvider: () -> Boolean
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            val application = extras.requireApplication()
            return DaggerPaymentMethodMessagingComponent.builder()
                .application(application)
                .configuration(configurationProvider())
                .isSystemDarkTheme(isSystemDarkThemeProvider())
                .build()
                .viewModel as T
        }
    }
}
