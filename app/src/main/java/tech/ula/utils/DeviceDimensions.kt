package tech.ula.utils

import android.content.SharedPreferences
import android.graphics.Point
import android.util.DisplayMetrics
import android.view.WindowManager
import tech.ula.BuildConfig

class DeviceDimensions {
    private var height = 720f
    private var width = 1480f
    private var scaling = 1f

    fun saveDeviceDimensions(windowManager: WindowManager, displayMetrics: DisplayMetrics, orientation: Int, sharedPreferences: SharedPreferences) {
        val navBarSize = getNavigationBarSize(windowManager)
        var scalingMin = 1f
        var scalingMax = 1f
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        height = displayMetrics.heightPixels.toFloat()
        width = displayMetrics.widthPixels.toFloat()

        /*
        if (navBarSize.y > (24 * displayMetrics.densityDpi / DisplayMetrics.DENSITY_DEFAULT)) {
            height -= navBarSize.y - (24 * displayMetrics.densityDpi / DisplayMetrics.DENSITY_DEFAULT)
        }
        if (navBarSize.x > (24 * displayMetrics.densityDpi / DisplayMetrics.DENSITY_DEFAULT)) {
            width -= navBarSize.x - (24 * displayMetrics.densityDpi / DisplayMetrics.DENSITY_DEFAULT)
        }
         */

        if (sharedPreferences.getBoolean("pref_custom_scaling_enabled", false)) {
            scaling = sharedPreferences.getString("pref_scaling", "1.0")!!.toFloat()
        } else {
            if (height > width) {
                scalingMax = height/BuildConfig.MAX_DIMENSION
                scalingMin = width/BuildConfig.MIN_DIMENSION
            } else {
                scalingMax = width/BuildConfig.MAX_DIMENSION
                scalingMin = height/BuildConfig.MIN_DIMENSION
            }
            if (scalingMin < 1f)
                scalingMin = 1f
            if (scalingMax < 1f)
                scalingMax = 1f

            if (scalingMax < scalingMin)
                scaling = scalingMax
            else
                scaling = scalingMin
        }

        height = height/scaling
        width = width/scaling
    }

    fun getScreenResolution(): String {
        return when ((height > width) && BuildConfig.FORCE_PORTRAIT_GEOMETRY) {
            true -> "${height.toInt()}x${width.toInt()}"
            false -> "${width.toInt()}x${height.toInt()}"
        }
    }

    private fun getNavigationBarSize(windowManager: WindowManager): Point {
        val display = windowManager.defaultDisplay
        val appSize = Point()
        val screenSize = Point()
        display.getSize(appSize)
        display.getRealSize(screenSize)

        return Point(screenSize.x - appSize.x, screenSize.y - appSize.y)
    }
}