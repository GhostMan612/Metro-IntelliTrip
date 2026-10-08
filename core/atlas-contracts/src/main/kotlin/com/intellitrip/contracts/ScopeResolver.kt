package com.intellitrip.contracts

import com.intellitrip.domain.CameraScope
import com.intellitrip.domain.DataScope
import com.intellitrip.domain.Focus
import com.intellitrip.domain.RenderScope

interface ScopePolicy {
    fun resolve(focus: Focus?): ScopeResolution
}

interface ScopeResolver {
    fun resolve(focus: Focus?): ScopeResolution
}

data class ScopeResolution(
    val cameraScope: CameraScope,
    val dataScope: DataScope,
    val renderScope: RenderScope,
)
