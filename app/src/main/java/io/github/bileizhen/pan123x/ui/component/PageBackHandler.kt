package io.github.bileizhen.pan123x.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/** Selection/search must consume back before a Navigation 3 scene is popped. */
internal class PageBackActions {
    private val actions = mutableStateListOf<Pair<Any, () -> Unit>>()
    val current: (() -> Unit)? get() = actions.lastOrNull()?.second
    fun register(key: Any, action: () -> Unit) { actions.add(key to action) }
    fun remove(key: Any) { actions.removeAll { it.first === key } }
}

internal val LocalPageBackActions = staticCompositionLocalOf<PageBackActions?> { null }
internal val LocalPageActive = staticCompositionLocalOf { true }

@Composable
internal fun PageBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val active = enabled && LocalPageActive.current
    val actions = LocalPageBackActions.current
    val key = remember { Any() }
    val callback = rememberUpdatedState(onBack)
    BackHandler(active) { callback.value() }
    DisposableEffect(actions, active) {
        if (active) actions?.register(key) { callback.value() }
        onDispose { actions?.remove(key) }
    }
}
