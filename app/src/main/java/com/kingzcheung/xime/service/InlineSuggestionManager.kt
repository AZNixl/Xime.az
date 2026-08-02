package com.kingzcheung.xime.service

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class InlineSuggestionManager {

    var suggestions by mutableStateOf<List<InlineSuggestion>>(emptyList())
        private set

    var isAvailable: Boolean = false
        private set

    var candidateTextColorArgb: Int = Color.BLACK
    var labelTextColorArgb: Int = Color.GRAY
    var backgroundColorArgb: Int = Color.WHITE

    @RequiresApi(Build.VERSION_CODES.R)
    fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        return try {
            val style = InlineSuggestionUi.newStyleBuilder()
                .setTitleStyle(
                    TextViewStyle.Builder()
                        .setTextColor(candidateTextColorArgb)
                        .setTextSize(15f)
                        .build()
                )
                .setSubtitleStyle(
                    TextViewStyle.Builder()
                        .setTextColor(labelTextColorArgb)
                        .setTextSize(12f)
                        .build()
                )
                .build()
            val styleBundle = UiVersions.newStylesBuilder()
                .addStyle(style)
                .build()
            val spec = InlinePresentationSpec.Builder(
                Size(0, 0), Size(800, 400)
            ).setStyle(styleBundle).build()
            InlineSuggestionsRequest.Builder(listOf(spec))
                .setMaxSuggestionCount(InlineSuggestionsRequest.SUGGESTION_COUNT_UNLIMITED)
                .build()
        } catch (e: Throwable) {
            Log.w("InlineSuggestionManager", "onCreateInlineSuggestionsRequest failed", e)
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        isAvailable = true
        val newSuggestions = response.inlineSuggestions
        if (newSuggestions.isEmpty() && suggestions.isNotEmpty()) {
            return true
        }
        suggestions = newSuggestions
        return true
    }

    fun clear() {
        suggestions = emptyList()
        isAvailable = false
    }
}
