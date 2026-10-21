package com.phoneform.desktop.security

import com.sun.jna.Platform
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt
import java.io.IOException

/** Production implementations must authenticate wrapped material and fail closed. */
interface KeyProtector {
    fun protect(plain: ByteArray, purpose: String): ByteArray
    fun unprotect(wrapped: ByteArray, purpose: String): ByteArray
}

/** Windows CurrentUser DPAPI; machine-wide protection and interactive prompts are never enabled. */
object DpapiKeyProtector : KeyProtector {
    override fun protect(plain: ByteArray, purpose: String): ByteArray {
        checkWindows()
        return Crypt32Util.cryptProtectData(plain, purpose.toByteArray(Charsets.UTF_8),
            WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, "PhoneForm PC storage", null)
    }

    override fun unprotect(wrapped: ByteArray, purpose: String): ByteArray {
        checkWindows()
        return try {
            Crypt32Util.cryptUnprotectData(wrapped, purpose.toByteArray(Charsets.UTF_8),
                WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null)
        } catch (failure: Exception) {
            throw IOException("Storage keys are unavailable for this Windows user", failure)
        }
    }

    private fun checkWindows() {
        if (!Platform.isWindows()) throw IOException("Windows CurrentUser DPAPI is required")
    }
}
