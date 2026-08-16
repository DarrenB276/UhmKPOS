package com.uhmk.pos.core.sync

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp

/**
 * Decides whether this build is pointed at a real Firebase project.
 *
 * The repo ships a structurally valid but obviously fake `google-services.json` so the app
 * compiles and runs before Firebase has been set up. Every cloud code path checks here first and
 * quietly stays local when the config is still the placeholder — that is what lets the test APK
 * work on day one.
 */
object FirebaseGate {

    private const val TAG = "FirebaseGate"
    private const val PLACEHOLDER_PROJECT = "uhmk-pos-placeholder"
    private const val PLACEHOLDER_SENDER = "000000000000"

    @Volatile
    private var cached: Boolean? = null

    fun isConfigured(context: Context): Boolean {
        cached?.let { return it }

        val result = runCatching {
            val app = FirebaseApp.getInstance()
            val options = app.options
            val projectId = options.projectId.orEmpty()
            val senderId = options.gcmSenderId.orEmpty()

            projectId.isNotBlank() &&
                projectId != PLACEHOLDER_PROJECT &&
                senderId != PLACEHOLDER_SENDER
        }.getOrElse {
            Log.i(TAG, "Firebase not initialised; running local-only. ${it.message}")
            false
        }

        cached = result
        Log.i(TAG, if (result) "Firebase configured — cloud sync active." else "Placeholder config — local mode.")
        return result
    }

    /** Test hook / used after the user swaps in a real config and restarts. */
    fun reset() {
        cached = null
    }
}
