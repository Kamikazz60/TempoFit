package com.doanh.musictraining

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class MusicService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    override fun onCreate() {
        super.onCreate()
        val p = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            // Also covers automatic next/previous and headset controls while UI is closed.
            addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    playbackParameters = PlaybackParameters(1f, 1f)
                }
            })
        }
        player = p
        session = MediaSession.Builder(this, p).build()
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() { session?.release(); player?.release(); session = null; player = null; super.onDestroy() }
}
