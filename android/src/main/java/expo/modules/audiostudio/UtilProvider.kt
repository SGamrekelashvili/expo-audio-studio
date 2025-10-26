package expo.modules.audiostudio

import android.content.Context

interface UtilProvider {
    fun fileCacheLocationFullPath(context: Context, fileName: String): String
    fun convertToSecondsFormatted(seconds: Float): String
}
