package expo.modules.audiostudio.player

data class PlayerProgress(
    val percentage: Float = 0f,
    val currentSeconds: Int = 0,
    val duration: Int = 0
)
