package com.phoneform.desktop

/** Matches the persisted viewport contract in CanvasViewState.normalized. */
internal object CanvasLimits {
    const val MIN_ZOOM=.5f
    const val MAX_ZOOM=5f
    fun zoom(value:Float)=if(value.isFinite())value.coerceIn(MIN_ZOOM,MAX_ZOOM)else 1f
}
