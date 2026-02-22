package com.kmpnavbuilder.runtime

import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.router.webhistory.WebNavigationOwner

/**
 * Implemented by generated ChildComponent sealed classes.
 * Allows NavRootComponent to chain web navigation into flow sub-screens.
 */
@OptIn(ExperimentalDecomposeApi::class)
interface NavChildComponent {
    fun asWebNavigationOwner(): WebNavigationOwner?
}
