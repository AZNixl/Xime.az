package com.kingzcheung.xime.service

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.Window
import android.view.WindowInsets
import kotlin.math.roundToInt

private const val INSETS_TAG = "ImeWindowInsets"

/** 获取导航栏高度（dp）。 */
internal fun tryGetNavBarHeightDp(context: Context, imeWindow: Window?): Int {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val decorView = imeWindow?.decorView
            if (decorView != null) {
                val insets = decorView.rootWindowInsets
                if (insets != null) {
                    val px = insets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.navigationBars()
                    ).bottom
                    val dp = (px / context.resources.displayMetrics.density).roundToInt()
                    return dp
                }
            }
        }
        val resId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val dp = if (resId > 0) (context.resources.getDimensionPixelSize(resId) / context.resources.displayMetrics.density).roundToInt() else 0
        return dp
    } catch (e: Exception) {
        Log.w(INSETS_TAG, "NavBar: error", e)
        0
    }
}

/** 获取可见导航栏高度（dp）。 */
internal fun tryGetVisibleNavBarHeightDp(context: Context, imeWindow: Window?): Int {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val decorView = imeWindow?.decorView
            if (decorView != null) {
                val insets = decorView.rootWindowInsets
                if (insets != null) {
                    val px = insets.getInsets(
                        WindowInsets.Type.navigationBars()
                    ).bottom
                    val dp = if (px > 0) (px / context.resources.displayMetrics.density).roundToInt() else 0
                    return dp
                }
            }
        }
        0
    } catch (e: Exception) {
        Log.w(INSETS_TAG, "NavBar: visibleOnly error", e)
        0
    }
}

/** 获取状态栏高度（dp）。 */
internal fun tryGetStatusBarHeightDp(context: Context, imeWindow: Window?): Int {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val decorView = imeWindow?.decorView
            if (decorView != null) {
                val insets = decorView.rootWindowInsets
                if (insets != null) {
                    val px = insets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.statusBars()
                    ).top
                    if (px > 0) return (px / context.resources.displayMetrics.density).roundToInt()
                }
            }
        }
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resId > 0) (context.resources.getDimensionPixelSize(resId) / context.resources.displayMetrics.density).roundToInt() else 0
    } catch (e: Exception) { 0 }
}

/**
 * 多类型检测底部 inset。
 * navigationBars 为 0 但仍有底部区域：检查 systemBars / tappableElement 等。
 * 手势导航下 navigationBars 通常为 0，但 mandatorySystemGestures / systemGestures
 * 会返回手势条高度（约 20~32dp），阈值不能过滤掉它们，否则底部手势条区域会露出窗口背景。
 */
internal fun extractBottomInset(
    insets: WindowInsets
): Int {
    val sys = insets.getInsets(WindowInsets.Type.systemBars()).bottom
    if (sys > 0) return sys
    val nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
    if (nav > 0) return nav
    val tappable = insets.getInsets(WindowInsets.Type.tappableElement()).bottom
    if (tappable > 0) return tappable
    val mandatory = insets.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom
    if (mandatory > 0) return mandatory
    val gestures = insets.getInsets(WindowInsets.Type.systemGestures()).bottom
    if (gestures > 0) return gestures
    return 0
}

/** 获取当前活跃的底部 inset（px）。 */
internal fun getActiveBottomInsetPx(imeWindow: Window?): Int {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
    return try {
        val decorView = imeWindow?.decorView ?: return 0
        val insets = decorView.rootWindowInsets ?: return 0
        extractBottomInset(insets)
    } catch (e: Exception) { 0 }
}
