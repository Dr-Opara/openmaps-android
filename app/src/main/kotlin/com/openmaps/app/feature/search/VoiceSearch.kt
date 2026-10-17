package com.openmaps.app.feature.search

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openmaps.app.R
import java.util.Locale

/**
 * Remembers a voice-search launcher backed by Android's own speech recognizer.
 *
 * Deliberately the **system** recognizer (`RecognizerIntent`) rather than an in-process
 * [SpeechRecognizer]: it needs no native handle to release, no runtime permission (the platform
 * shows its own microphone consent, and the app declares no `RECORD_AUDIO`), and it is present on
 * every device that can do voice input at all.
 *
 * Every outcome is reported — heard, cancelled, unavailable, error — so the microphone can never be
 * a control that silently does nothing.
 */
@Composable
fun rememberVoiceSearchLauncher(
    onStarted: () -> Unit,
    onUnavailable: () -> Unit,
    onFailed: (String) -> Unit,
    onCancelled: () -> Unit,
    onResult: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val failedMessage = stringResource(R.string.voice_search_failed)
    val prompt = stringResource(R.string.voice_search_prompt)

    // `rememberUpdatedState` so the callbacks stay current without recreating the launcher.
    val started by rememberUpdatedState(onStarted)
    val unavailable by rememberUpdatedState(onUnavailable)
    val failed by rememberUpdatedState(onFailed)
    val cancelled by rememberUpdatedState(onCancelled)
    val received by rememberUpdatedState(onResult)

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { outcome ->
        if (outcome.resultCode != Activity.RESULT_OK) {
            // The user dismissed the system recognizer without speaking.
            cancelled()
            return@rememberLauncherForActivityResult
        }
        val text = outcome.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()
            .trim()
        if (text.isEmpty()) cancelled() else received(text)
    }

    return {
        val activity = context.findActivityOrNull()
        if (activity == null || !SpeechRecognizer.isRecognitionAvailable(activity)) {
            // No recognizer on this device: say so rather than launching a dialog that cannot work.
            unavailable()
        } else {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            }
            try {
                started()
                launcher.launch(intent)
            } catch (_: ActivityNotFoundException) {
                // Nothing can handle it despite isRecognitionAvailable() saying yes.
                unavailable()
            }
        }
    }
}


private tailrec fun Context.findActivityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivityOrNull()
    else -> null
}