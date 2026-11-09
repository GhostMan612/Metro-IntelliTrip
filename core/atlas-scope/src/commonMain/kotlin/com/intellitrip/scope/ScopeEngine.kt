package com.intellitrip.scope

import com.intellitrip.contracts.ScopeResolver
import com.intellitrip.domain.Focus
import com.intellitrip.domain.ScopeState

/**
 * Combines a focus with its resolved scopes into the immutable [ScopeState]
 * consumed by the render pipeline and by data requests.
 */
class ScopeEngine(private val resolver: ScopeResolver = DefaultScopeResolver()) {

    fun currentScope(focus: Focus?): ScopeState {
        val resolution = resolver.resolve(focus)
        return ScopeState(
            focus = focus,
            cameraScope = resolution.cameraScope,
            dataScope = resolution.dataScope,
            renderScope = resolution.renderScope,
        )
    }
}