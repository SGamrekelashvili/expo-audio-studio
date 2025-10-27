package expo.modules.audiostudio

import android.content.Context
import java.io.File

class AndroidUtilProvider : UtilProvider {
    override fun convertToSecondsFormatted(seconds: Float): String {
        val minutes = (seconds / 60).toInt()
        val remainingSeconds = (seconds % 60).toInt()
        return String.format("%02d:%02d", minutes, remainingSeconds)
    }

    override fun fileCacheLocationFullPath(context: Context, fileName: String): String {
        val directory = context.cacheDir
        val file = File(directory, fileName)
        return file.absolutePath
    }
}
