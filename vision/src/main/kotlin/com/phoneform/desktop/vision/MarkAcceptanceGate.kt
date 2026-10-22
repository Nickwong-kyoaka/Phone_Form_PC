package com.phoneform.desktop.vision

import com.phoneform.ai.core.model.MarkStyle

/** Frozen anonymous option AND question-group acceptance; this is not a general-handwriting claim. */
object MarkAcceptanceGate {
    private const val ACCEPTED_PARAMETERS = "48fd559a1b9fd3fa4298422535145286bf2dd36c90ba719bede5da4f2685fab8"
    fun enabled(style: MarkStyle): Boolean = MarkParameters.VERSION=="marks-1" && MarkParameters.fingerprint==ACCEPTED_PARAMETERS && when (style) {
        // Both frozen option-crop and independent complete-question holdouts passed; every candidate still requires review.
        MarkStyle.CHECKBOX_TICK_CROSS -> true
        MarkStyle.CIRCLED_PRINTED_NUMBER -> true
        MarkStyle.MANUAL_ONLY -> false
    }
}
