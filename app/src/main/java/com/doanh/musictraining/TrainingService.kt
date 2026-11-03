package com.doanh.musictraining

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.SoundPool
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow

/** Session state is owned by the foreground service, never by the Activity. */
data class TrainingState(val active: Boolean = false, val paused: Boolean = false, val phase: String = "Prêt", val round: Int = 1, val rounds: Int = 8, val seconds: Int = 0)

object TrainingBus {
    val state = MutableStateFlow(TrainingState())
    const val CHANNEL = "training_active"
    const val NOTIFICATION_ID = 4102
}

class TrainingService : Service() {
    companion object {
        const val START = "training.start"
        const val PAUSE = "training.pause"
        const val RESUME = "training.resume"
        const val STOP = "training.stop"
        const val NEXT = "training.next"
        const val WORK = "work"
        const val REST = "rest"
        const val ROUNDS = "rounds"
        const val PREP = "prep"
        const val SIGNAL_VOLUME = "signal_volume"
        const val SIGNAL_PROFILE = "signal_profile"
        // Overall attenuation of the beeps (-6 dB): they were too loud even at the minimum slider setting.
        const val SIGNAL_GAIN = .5f
    }
    private val handler = Handler(Looper.getMainLooper())
    private var sounds: SoundPool? = null
    private var workSound = 0
    private var restSound = 0
    private var finishSound = 0
    private var graveSounds = IntArray(3)
    private var aiguSounds = IntArray(3)
    private val pendingSounds = mutableListOf<Runnable>()
    private var focusRequest: AudioFocusRequest? = null
    private val releaseDuck = Runnable { releaseDuckNow() }
    private var finishing = false
    private var workSeconds = 40
    private var restSeconds = 20
    private var maxRounds = 8
    private var prepSeconds = 0
    private var signalVolume = 1f
    private var lastCountdownSecond = -1
    private var phase = "Travail"
    private var round = 1
    private var deadline = 0L
    private var remainingMs = 0L
    private var running = false
    private var lastShown = -1
    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            // Catch up after delayed callbacks (screen off / scheduler delays).
            var safety = 0
            while (running && now >= deadline && safety++ < 10000) advance(fromDeadline = true)
            if (running) {
                val seconds = ((deadline - now + 999L) / 1000L).coerceAtLeast(0L).toInt()
                if (seconds != lastShown) {
                    lastShown = seconds
                    publish(seconds)
                    if ((phase == "Travail" || phase == "Préparation" || (phase == "Repos" && restSeconds > 3)) && seconds in 1..3 && seconds != lastCountdownSecond) {
                        lastCountdownSecond = seconds
                        playSound(workSound)
                    }
                }
                handler.postDelayed(this, 150L)
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(TrainingBus.CHANNEL, "Entraînement en cours", NotificationManager.IMPORTANCE_LOW)
        )
        // Same stream as the music (USAGE_MEDIA) so Bluetooth speakers receive the signals too;
        // system/sonification streams are often kept on the phone speaker.
        sounds = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        ).build()
        aiguSounds = intArrayOf(loadSound("work.wav"), loadSound("rest.wav"), loadSound("finish.wav"))
        graveSounds = intArrayOf(loadSound("work_grave.wav"), loadSound("rest_grave.wav"), loadSound("finish_grave.wav"))
        selectProfile("aigu")
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> {
                workSeconds = intent.getIntExtra(WORK, 40).coerceIn(1, 36000)
                restSeconds = intent.getIntExtra(REST, 20).coerceIn(1, 36000)
                maxRounds = intent.getIntExtra(ROUNDS, 8).coerceIn(1, 999)
                signalVolume = intent.getFloatExtra(SIGNAL_VOLUME, 1f).coerceIn(.1f, 1f)
                selectProfile(intent.getStringExtra(SIGNAL_PROFILE) ?: "aigu")
                prepSeconds = intent.getIntExtra(PREP, 0).coerceIn(0, 3600)
                phase = if (prepSeconds > 0) "Préparation" else "Travail"; round = 1
                remainingMs = (if (prepSeconds > 0) prepSeconds else workSeconds) * 1000L
                deadline = SystemClock.elapsedRealtime() + remainingMs
                running = true; finishing = false; lastShown = -1; lastCountdownSecond = -1
                cancelSounds()
                startForeground(TrainingBus.NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                if (phase == "Travail") signalWork(); handler.removeCallbacks(ticker); handler.post(ticker)
            }
            PAUSE -> if (running) {
                remainingMs = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                running = false; handler.removeCallbacks(ticker)
                publish(((remainingMs + 999L) / 1000L).toInt())
            }
            RESUME -> if (!running && TrainingBus.state.value.active) {
                deadline = SystemClock.elapsedRealtime() + remainingMs
                running = true; lastShown = -1; handler.post(ticker)
            }
            NEXT -> if (TrainingBus.state.value.active) {
                // Manual skip starts a FULL new phase; it must not carry over unused time.
                advance(fromDeadline = false)
                if (running) { handler.removeCallbacks(ticker); handler.post(ticker) }
            }
            STOP -> { finish(); return START_NOT_STICKY }
        }
        return START_NOT_STICKY
    }
    private fun advance(fromDeadline: Boolean) {
        if (phase == "Travail" && round == maxRounds) { finish(); return }
        val nextDuration: Long
        if (phase == "Préparation") {
            phase = "Travail"
            nextDuration = workSeconds * 1000L
        } else if (phase == "Travail") {
            phase = "Repos"
            nextDuration = restSeconds * 1000L
        } else {
            phase = "Travail"
            round++
            nextDuration = workSeconds * 1000L
        }
        // Automatic transitions retain the scheduled boundary to compensate for
        // delayed Android callbacks; manual skips reset the clock to NOW.
        deadline = (if (fromDeadline) deadline else SystemClock.elapsedRealtime()) + nextDuration
        remainingMs = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        lastShown = -1
        lastCountdownSecond = -1
        if (phase == "Travail") signalWork() else signalRest()
        publish(((remainingMs + 999L) / 1000L).toInt())
    }
    private fun publish(seconds: Int) {
        TrainingBus.state.value = TrainingState(true, !running, phase, round, maxRounds, seconds)
        getSystemService(NotificationManager::class.java).notify(TrainingBus.NOTIFICATION_ID, notification())
    }
    private fun selectProfile(profile: String) {
        val set = if (profile == "grave") graveSounds else aiguSounds
        workSound = set[0]; restSound = set[1]; finishSound = set[2]
    }

    private fun loadSound(name: String): Int = try {
        assets.openFd(name).use { descriptor -> sounds?.load(descriptor, 1) ?: 0 }
    } catch (_: Exception) { 0 }

    private fun cancelSounds() {
        pendingSounds.forEach(handler::removeCallbacks)
        pendingSounds.clear()
    }

    private fun playSound(id: Int, holdMs: Long = 500L) {
        if (id == 0) return
        duckMusic(holdMs)
        val gain = signalVolume * SIGNAL_GAIN
        sounds?.play(id, gain, gain, 1, 0, 1f)
    }

    // Ask for transient "may duck" focus so the music player lowers its volume while a signal plays.
    private fun duckMusic(holdMs: Long) {
        val manager = getSystemService(AudioManager::class.java) ?: return
        if (focusRequest == null) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setOnAudioFocusChangeListener { }.build()
            if (manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focusRequest = request
        }
        handler.removeCallbacks(releaseDuck)
        handler.postDelayed(releaseDuck, holdMs)
    }

    private fun releaseDuckNow() {
        handler.removeCallbacks(releaseDuck)
        focusRequest?.let { getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun signalWork() {
        cancelSounds()
        playSound(workSound)
    }

    private fun signalRest() {
        cancelSounds()
        // Three separate, lower-pitched pulses; music playback is untouched.
        playSound(restSound)
        for (delay in listOf(360L, 720L)) {
            val task = Runnable { playSound(restSound) }
            pendingSounds.add(task)
            handler.postDelayed(task, delay)
        }
    }

    private fun notification(): Notification {
        val state = TrainingBus.state.value
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, TrainingService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, TrainingBus.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("TempoFit · ${state.phase}")
            .setContentText("Tour ${state.round}/${state.rounds} · ${state.seconds}s")
            .setContentIntent(open).addAction(android.R.drawable.ic_menu_close_clear_cancel, "Arrêter", stop)
            .setOngoing(true).setOnlyAlertOnce(true).build()
    }
    private fun finish() {
        if (finishing) return
        finishing = true
        running = false; handler.removeCallbacks(ticker); cancelSounds()
        TrainingBus.state.value = TrainingState(phase = "Terminé", rounds = maxRounds)
        playSound(finishSound, 1300L)
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Keep SoundPool alive long enough to play the entire completion chime.
        handler.postDelayed({ stopSelf() }, 1700L)
    }
    override fun onDestroy() { handler.removeCallbacks(ticker); cancelSounds(); releaseDuckNow(); sounds?.release(); sounds = null; super.onDestroy() }
}
