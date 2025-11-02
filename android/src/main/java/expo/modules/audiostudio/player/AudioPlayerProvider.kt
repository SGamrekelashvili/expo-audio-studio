package expo.modules.audiostudio.player

import android.content.Context

interface AudioPlayerProvider {
    fun preparePlayer(fileName: String, AudioEndFunction: (result: Map<String, Boolean>) -> Unit): Boolean
    fun startPlaying(fileName: String, AudioEndFunction: (result: Map<String, Boolean>) -> Unit): Boolean
    fun stopPlaying(): Boolean
    fun pausePlaying(): Boolean
    fun resumePlaying(): Boolean
    fun seekTo(position: Int): Boolean
    fun isPlaying(): Boolean
    fun getPlaybackSpeed(): Float
    fun getAudioDuration(uri: String): Long
    fun getCurrentPosition(): Int
    fun playerStatus(): PlayerProgress?
    fun releasePlayer()
    fun setPlaybackSpeed(speed: String): Boolean
}
