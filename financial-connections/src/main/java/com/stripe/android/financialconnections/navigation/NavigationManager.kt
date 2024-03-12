package com.stripe.android.financialconnections.navigation

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject

internal interface NavigationManager {
    val navigationFlow: SharedFlow<NavigationIntent>

    fun tryNavigateTo(
        route: String,
        popUpTo: PopUpToBehavior? = null,
        isSingleTop: Boolean = true,
    )

    fun tryNavigateBack()
}

internal sealed interface PopUpToBehavior {
    val inclusive: Boolean

    data class Current(
        override val inclusive: Boolean,
    ) : PopUpToBehavior

    data class Route(
        override val inclusive: Boolean,
        val route: String,
    ) : PopUpToBehavior

    data object LaunchAsRoot : PopUpToBehavior {
        override val inclusive: Boolean = true
    }
}

internal sealed class NavigationIntent {

    data class NavigateTo(
        val route: String,
        val popUpTo: PopUpToBehavior?,
        val isSingleTop: Boolean,
    ) : NavigationIntent()

    data object NavigateBack : NavigationIntent()
}

internal class NavigationManagerImpl @Inject constructor() : NavigationManager {
    private val _navigationFlow = MutableSharedFlow<NavigationIntent>(extraBufferCapacity = 1)

    override val navigationFlow = _navigationFlow.asSharedFlow()

    override fun tryNavigateTo(
        route: String,
        popUpTo: PopUpToBehavior?,
        isSingleTop: Boolean,
    ) {
        val popUpToBehavior = popUpTo ?: run {
            val destination = Destination.fromRoute(route.split("/").first())
            destination?.let {
                if (it.launchAsRoot) {
                    PopUpToBehavior.LaunchAsRoot
                } else {
                    null
                }
            }
        }

        _navigationFlow.tryEmit(
            NavigationIntent.NavigateTo(
                route = route,
                popUpTo = popUpToBehavior,
                isSingleTop = isSingleTop,
            )
        )
    }

    override fun tryNavigateBack() {
        _navigationFlow.tryEmit(NavigationIntent.NavigateBack)
    }
}
