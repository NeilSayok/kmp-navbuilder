package com.kmpnavbuilder.runtime

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.childStackWebNavigation
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.pushNew
import com.arkivanov.decompose.router.stack.replaceAll
import com.arkivanov.decompose.router.webhistory.WebNavigation
import com.arkivanov.decompose.router.webhistory.WebNavigationOwner
import com.arkivanov.decompose.value.Value
import kotlinx.serialization.KSerializer

@OptIn(ExperimentalDecomposeApi::class)
abstract class NavRootComponent<C : NavConfig, T : NavChildComponent>(
    componentContext: ComponentContext,
    serializer: KSerializer<C>,
    initialStack: () -> List<C>,
    childFactory: (config: C, context: ComponentContext, navigateTo: (NavConfig, Boolean?) -> Unit) -> T,
    private val pathMapper: ((C) -> String)? = null,
) : ComponentContext by componentContext, WebNavigationOwner {

    val navigation = StackNavigation<C>()

    private val _stack: Value<ChildStack<C, T>> = childStack(
        source = navigation,
        serializer = serializer,
        initialStack = initialStack,
        handleBackButton = true,
        childFactory = { config, context ->
            childFactory(config, context, ::handleNavigation)
        },
    )

    val stack: Value<ChildStack<C, T>> = _stack

    override val webNavigation: WebNavigation<*> =
        childStackWebNavigation(
            navigator = navigation,
            stack = _stack,
            serializer = serializer,
            pathMapper = { pathMapper?.invoke(it.configuration) },
            childSelector = { it.instance.asWebNavigationOwner() },
        )

    @Suppress("UNCHECKED_CAST")
    private fun handleNavigation(navConfig: NavConfig, popBackStack: Boolean?) {
        val config = navConfig as C
        if (popBackStack == true) {
            navigation.replaceAll(config)
        } else {
            navigation.pushNew(config)
        }
    }

    fun navigateBack() {
        navigation.pop()
    }
}
