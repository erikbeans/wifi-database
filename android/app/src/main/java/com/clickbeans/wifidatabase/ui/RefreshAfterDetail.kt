package com.clickbeans.wifidatabase.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry

const val REFRESH_AFTER_DETAIL = "refresh_after_detail"

@Composable
fun RefreshAfterDetail(refresh: () -> Unit) {
    val entry = LocalLifecycleOwner.current as? NavBackStackEntry
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (entry?.savedStateHandle?.remove<Boolean>(REFRESH_AFTER_DETAIL) == true) refresh()
    }
}
