package com.kingzcheung.xime.ui.keyboard

import android.os.Build
import android.util.Log
import android.util.Size
import android.view.inputmethod.InlineSuggestion
import android.widget.inline.InlineContentView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.Executors
import java.util.function.Consumer

private const val TAG = "InlineSuggestionView"

@Composable
fun InlineSuggestionView(
    suggestion: Any?,
    modifier: Modifier = Modifier,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

    val realSuggestion = suggestion as? InlineSuggestion ?: return
    val context = LocalContext.current
    var inflatedView by remember { mutableStateOf<InlineContentView?>(null) }

    androidx.compose.runtime.LaunchedEffect(suggestion) {
        Log.d(TAG, "inflating suggestion")
        val executor = Executors.newSingleThreadExecutor()
        realSuggestion.inflate(
            context,
            Size(Int.MAX_VALUE, Int.MAX_VALUE),
            executor,
            Consumer { contentView ->
                Log.d(TAG, "inflate callback fired: $contentView")
                inflatedView = contentView
            },
        )
    }

    val view = inflatedView
    if (view != null) {
        Box(modifier = modifier) {
            AndroidView(
                factory = { view },
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
fun InlineSuggestionDivider(
    modifier: Modifier = Modifier,
    color: Color = Color(0xFFDADCE0),
) {
    Box(
        modifier = modifier
            .width(1.dp)
            .fillMaxHeight()
            .padding(vertical = 6.dp)
            .background(color),
    )
}
