package com.parthajb.homereel.tv

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.VideoSupportFragment
import androidx.leanback.app.VideoSupportFragmentGlueHost
import androidx.leanback.media.PlaybackTransportControlGlue
import androidx.leanback.widget.Action
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.PlaybackControlsRow
import androidx.media3.common.C
import androidx.media3.common.Format as MediaFormat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.leanback.LeanbackPlayerAdapter
import com.parthajb.homereel.shared.Kind
import com.parthajb.homereel.shared.ApiJson
import com.parthajb.homereel.shared.Movie
import com.parthajb.homereel.shared.Playability
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class PlaybackActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_fragment)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.fragment, PlaybackFragment()).commit()
        }
    }

    companion object {
        const val EXTRA_MOVIE = "movie"
        const val EXTRA_START = "start"
    }
}

/**
 * Plays straight from the phone over HTTP, seeking with Range requests. Saves the position every 10 seconds and
 * when playback pauses or stops, so Resume works from any TV.
 */
@OptIn(markerClass = [UnstableApi::class])
class PlaybackFragment : VideoSupportFragment() {
    private val store by lazy { ServerStore(requireContext()) }
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var movie: Movie

    /** For an episode: the episodes after it, loaded from its show, for "Next episode". */
    private var upcoming: List<Movie> = emptyList()
    private var nextDialog: android.app.Dialog? = null
    private var skipDialog: android.app.Dialog? = null
    private var introOffered = false
    private var creditsOffered = false

    /** Cancel during the credits means "let me watch them": don't offer again, and stop at the end. */
    private var nextDeclined = false
    private var frameRateMatched = false

    /** Twice a second: Skip intro while inside the intro, Next episode once the credits start. */
    private val markerTick = object : Runnable {
        override fun run() {
            checkMarkers()
            main.postDelayed(this, 500)
        }
    }
    private var player: ExoPlayer? = null
    private var glue: MovieGlue? = null
    private var subtitleView: SubtitleView? = null
    private var ended = false

    private val tick = object : Runnable {
        override fun run() {
            if (player?.isPlaying == true) saveProgress()
            // "Ends at" moves while paused; refreshed only while the controls are hidden, so focus never jumps.
            if (!isControlsOverlayVisible) glue?.let(::label)
            main.postDelayed(this, 10_000)
        }
    }

    /** The small "» 10 s" note shown when Left or Right skips while the controls are hidden. */
    private var osd: TextView? = null
    private val hideOsd = Runnable { osd?.visibility = View.GONE }

    /** Set just before Leanback would show the controls for a key we handled ourselves. */
    private var skipTickle = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = requireActivity().intent
        movie = ApiJson.decodeFromString(Movie.serializer(), intent.getStringExtra(PlaybackActivity.EXTRA_MOVIE)!!)
        val start = intent.getLongExtra(PlaybackActivity.EXTRA_START, 0)
        val api = store.api()

        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(api.headers())
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(false)
        val dataSource = DefaultDataSource.Factory(requireContext(), http)
        val renderers = DefaultRenderersFactory(requireContext()).setEnableDecoderFallback(true)
        val exo = ExoPlayer.Builder(requireContext(), renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .build()
        player = exo
        applyTrackPrefs(exo)

        exo.setMediaItem(mediaItem(movie), start)
        loadUpcoming()
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = showError(error)

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && !ended) {
                    ended = true
                    saveProgress(atEnd = true)
                    val next = upcoming.firstOrNull()
                    when {
                        next != null && !nextDeclined && nextDialog == null -> offerNext(next, duringCredits = false)
                        nextDialog == null -> activity?.finish()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) saveProgress()
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                player?.videoFormat?.frameRate?.takeIf { it > 0 }?.let(::matchFrameRate)
            }

            override fun onCues(cueGroup: CueGroup) {
                subtitleView?.setCues(cueGroup.cues)
            }
        })
        exo.prepare()

        val adapter = LeanbackPlayerAdapter(requireContext(), exo, 500)
        glue = MovieGlue(requireContext(), adapter, exo).apply {
            host = VideoSupportFragmentGlueHost(this@PlaybackFragment)
            label(this)
            isSeekEnabled = true
            playWhenPrepared()
        }
        main.postDelayed(tick, 10_000)
        main.postDelayed(markerTick, 500)

        // Left and Right skip 10 seconds straight away while the controls are hidden, as on streaming apps;
        // with the controls showing they move between buttons as usual.
        setOnKeyInterceptListener { _, keyCode, event ->
            val by = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -10_000L
                KeyEvent.KEYCODE_DPAD_RIGHT -> 10_000L
                else -> return@setOnKeyInterceptListener false
            }
            if (isControlsOverlayVisible) return@setOnKeyInterceptListener false
            if (event.action == KeyEvent.ACTION_DOWN) {
                skipTickle = true
                quickSeek(by)
            }
            true
        }
    }

    /** Leanback shows the controls on every D-pad press, even one the key listener handled; this skips that once. */
    override fun tickle() {
        if (skipTickle) skipTickle = false else super.tickle()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val root = super.onCreateView(inflater, container, savedInstanceState) as ViewGroup
        val context = requireContext()
        // Above the video surface, below the controls, which Leanback adds later.
        subtitleView = SubtitleView(context).apply {
            setUserDefaultStyle()
            setUserDefaultTextSize()
        }
        root.addView(subtitleView, 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        osd = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(context, 20), dp(context, 10), dp(context, 20), dp(context, 10))
            background = GradientDrawable().apply {
                setColor(0xB3000000.toInt())
                cornerRadius = dp(context, 10).toFloat()
            }
            visibility = View.GONE
        }
        root.addView(osd, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(context, 56) })
        return root
    }

    override fun onDestroyView() {
        main.removeCallbacks(hideOsd)
        subtitleView = null
        osd = null
        super.onDestroyView()
    }

    private fun quickSeek(by: Long) {
        val p = player ?: return
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val to = (p.currentPosition + by).coerceIn(0, (duration - 1000).coerceAtLeast(0))
        p.seekTo(to)
        val arrow = if (by < 0) "«  ${-by / 1000} s" else "${by / 1000} s  »"
        showOsd("$arrow     ${Format.clock(to)} / ${Format.clock(duration)}")
    }

    private fun showOsd(text: String) {
        val view = osd ?: return
        view.text = text
        view.visibility = View.VISIBLE
        main.removeCallbacks(hideOsd)
        main.postDelayed(hideOsd, 1500)
    }

    /** Starts with the subtitles and audio language this profile chose last time. */
    private fun applyTrackPrefs(exo: ExoPlayer) {
        val prefs = TvPrefs(requireContext())
        val profile = store.profileId
        val params = exo.trackSelectionParameters.buildUpon()
        prefs.audioLanguage(profile)?.let { params.setPreferredAudioLanguage(it) }
        when (val subs = prefs.subtitleLanguage(profile)) {
            null -> Unit
            TvPrefs.OFF -> params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            TvPrefs.UNDETERMINED -> params.setSelectUndeterminedTextLanguage(true)
            else -> params.setPreferredTextLanguage(subs)
        }
        exo.trackSelectionParameters = params.build()
    }

    /** "Ends at 10:42 pm", allowing for the playback speed. */
    private fun endsAt(): String? {
        val context = context ?: return null
        val p = player ?: return null
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: movie.durationMs.takeIf { it > 0 } ?: return null
        val left = ((duration - p.currentPosition).coerceAtLeast(0) / p.playbackParameters.speed).toLong()
        val time = android.text.format.DateFormat.getTimeFormat(context).format(Date(System.currentTimeMillis() + left))
        return getString(R.string.ends_at, time)
    }

    override fun onPause() {
        glue?.pause()
        saveProgress()
        super.onPause()
    }

    override fun onDestroy() {
        nextDialog?.dismiss()
        skipDialog?.dismiss()
        main.removeCallbacks(tick)
        main.removeCallbacks(markerTick)
        player?.release()
        player = null
        worker.shutdown()
        super.onDestroy()
    }

    private fun saveProgress(atEnd: Boolean = false) {
        val p = player ?: return
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: movie.durationMs
        val position = if (atEnd) duration else p.currentPosition
        if (position <= 0 && !atEnd) return
        val api = store.api()
        // Captured now: by the time the worker runs, the player may have moved on to the next episode.
        val id = movie.id
        if (!worker.isShutdown) worker.execute { runCatching { api.progress(id, position, duration) } }
    }

    private fun mediaItem(m: Movie): MediaItem {
        val api = store.api()
        val subtitles = m.subtitles.map { s ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(api.url("/movies/${m.id}/subtitles/${s.index}")))
                .setMimeType(s.mime)
                .setLanguage(s.language)
                .setLabel(s.label)
                .build()
        }
        return MediaItem.Builder()
            .setUri(api.url("/movies/${m.id}/stream"))
            .setMediaId(m.id)
            .setSubtitleConfigurations(subtitles)
            .build()
    }

    /** "Panchayat · S2:E5 Title" for an episode; the title and year for a film. Both end with when it finishes. */
    private fun label(glue: MovieGlue) {
        val subtitle = if (movie.kind == Kind.EPISODE) {
            glue.title = movie.showTitle ?: movie.title
            listOfNotNull("${Format.episodeCode(movie)}  ${movie.title}", endsAt())
        } else {
            glue.title = movie.title
            listOfNotNull(movie.year?.toString(), movie.video?.let { Playability.describe(it.mime) }, endsAt())
        }.joinToString(" · ")
        if (glue.subtitle?.toString() != subtitle) glue.subtitle = subtitle
    }

    private fun loadUpcoming() {
        val showId = movie.showId ?: return
        if (movie.kind != Kind.EPISODE) return
        val currentId = movie.id
        val api = store.api()
        worker.execute {
            val episodes = runCatching { api.show(showId).episodes }.getOrNull() ?: return@execute
            val after = episodes.dropWhile { it.id != currentId }.drop(1)
            main.post { if (movie.id == currentId) upcoming = after }
        }
    }

    /**
     * The "Next episode" countdown, in a small dialog of its own: the player's controls take every D-pad press,
     * a separate window keeps Play now and Cancel reachable.
     */
    private fun checkMarkers() {
        val p = player ?: return
        if (!p.isPlaying) return
        val pos = p.currentPosition
        val start = movie.introStartMs
        val end = movie.introEndMs
        val inIntro = start != null && end != null && pos >= start && pos < end - 2000
        if (inIntro && !introOffered) {
            introOffered = true
            offerSkipIntro(end!!)
        } else if (!inIntro && skipDialog != null) {
            skipDialog?.dismiss()
            skipDialog = null
        }
        val credits = movie.creditsStartMs
        val next = upcoming.firstOrNull()
        if (credits != null && next != null && pos >= credits && !creditsOffered && !nextDeclined && nextDialog == null) {
            creditsOffered = true
            offerNext(next, duringCredits = true)
        }
    }

    /** A small "Skip intro" button, in its own window like the Next episode panel, so the remote reaches it. */
    private fun offerSkipIntro(introEnd: Long) {
        val context = context ?: return
        val view = LayoutInflater.from(context).inflate(R.layout.skip_intro, null)
        val dialog = android.app.Dialog(context).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(view)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setGravity(android.view.Gravity.BOTTOM or android.view.Gravity.END)
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        view.findViewById<Button>(R.id.skip_intro).setOnClickListener {
            player?.seekTo(introEnd)
            dialog.dismiss()
            skipDialog = null
        }
        skipDialog = dialog
        dialog.show()
        view.findViewById<Button>(R.id.skip_intro).requestFocus()
    }

    /**
     * Switches the TV to the video's frame rate, or a whole multiple of it (25 fps on a 50 Hz mode), at the same
     * resolution, so films don't judder. The TV goes back to its usual mode when the player closes.
     */
    @Suppress("DEPRECATION")
    private fun matchFrameRate(fps: Float) {
        if (frameRateMatched || !TvPrefs(requireContext()).matchFrameRate) return
        frameRateMatched = true
        val window = activity?.window ?: return
        val display = if (android.os.Build.VERSION.SDK_INT >= 30) activity?.display else window.windowManager.defaultDisplay
        display ?: return
        val current = display.mode
        val sameSize = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        val best = sameSize.firstOrNull { kotlin.math.abs(it.refreshRate - fps) < 0.05f }
            ?: sameSize.firstOrNull { kotlin.math.abs(it.refreshRate - 2 * fps) < 0.1f }
            ?: return
        if (best.modeId == current.modeId) return
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }

    private fun offerNext(next: Movie, duringCredits: Boolean) {
        val context = context ?: return
        val view = LayoutInflater.from(context).inflate(R.layout.next_episode, null)
        val text = view.findViewById<TextView>(R.id.next_text)
        val dialog = android.app.Dialog(context).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(view)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setGravity(android.view.Gravity.BOTTOM or android.view.Gravity.END)
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setOnCancelListener { if (duringCredits) declineNext() else activity?.finish() }
        }
        var left = 10
        val label = "${Format.episodeCode(next)}  ${next.title}"
        val countdown = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                text.text = getString(R.string.next_in, label, left)
                if (left-- <= 0) playNext(next) else main.postDelayed(this, 1000)
            }
        }
        view.findViewById<Button>(R.id.next_play).setOnClickListener { playNext(next) }
        view.findViewById<Button>(R.id.next_cancel).setOnClickListener {
            dialog.dismiss()
            if (duringCredits) declineNext() else activity?.finish()
        }
        nextDialog = dialog
        dialog.show()
        view.findViewById<Button>(R.id.next_play).requestFocus()
        main.post(countdown)
    }

    private fun declineNext() {
        nextDialog = null
        nextDeclined = true
        if (ended) activity?.finish()
    }

    private fun playNext(next: Movie) {
        nextDialog?.dismiss()
        nextDialog = null
        skipDialog?.dismiss()
        skipDialog = null
        introOffered = false
        creditsOffered = false
        nextDeclined = false
        val p = player ?: return
        movie = next
        upcoming = upcoming.drop(1)
        ended = false
        p.setMediaItem(mediaItem(next), next.positionMs.takeIf { MainActivity.inProgress(next) } ?: 0)
        p.prepare()
        p.play()
        glue?.let(::label)
    }

    private fun showError(error: PlaybackException) {
        val context = context ?: return
        val message = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            -> getString(R.string.error_network, store.name ?: "the phone")
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> getString(R.string.error_http)
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            -> getString(R.string.error_codec)
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            -> getString(R.string.error_container)
            else -> getString(R.string.error_other, error.errorCodeName)
        }
        AlertDialog.Builder(context)
            .setTitle(movie.title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> activity?.finish() }
            .setOnCancelListener { activity?.finish() }
            .show()
    }

    /**
     * The standard controls plus −10 s / +30 s, and three pickers: subtitles (with Off), the audio language of a
     * dubbed film, and playback speed. Subtitles and audio are remembered per profile for the next film.
     */
    private inner class MovieGlue(context: Context, adapter: LeanbackPlayerAdapter, private val player: ExoPlayer) :
        PlaybackTransportControlGlue<LeanbackPlayerAdapter>(context, adapter) {
        private val rewind = PlaybackControlsRow.RewindAction(context)
        private val forward = PlaybackControlsRow.FastForwardAction(context)
        private val captions = PlaybackControlsRow.ClosedCaptioningAction(context)
        private val audio = Action(ACTION_AUDIO, context.getString(R.string.audio_track), null,
            ContextCompat.getDrawable(context, R.drawable.ic_audio))
        private val speed = Action(ACTION_SPEED, context.getString(R.string.speed), null, speedIcon(1f))

        init {
            player.addListener(object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) = syncCaptions(tracks.isTypeSelected(C.TRACK_TYPE_TEXT))

                // Some TVs can't speed up passthrough sound (Dolby to an amplifier); the player then stays at 1×.
                override fun onPlaybackParametersChanged(params: PlaybackParameters) {
                    speed.icon = speedIcon(params.speed)
                    notifyActionChanged(speed)
                }
            })
        }

        override fun onCreatePrimaryActions(primary: ArrayObjectAdapter) {
            primary.add(rewind)
            super.onCreatePrimaryActions(primary)
            primary.add(forward)
        }

        override fun onCreateSecondaryActions(secondary: ArrayObjectAdapter) {
            secondary.add(captions)
            secondary.add(audio)
            secondary.add(speed)
        }

        override fun onActionClicked(action: Action) {
            when (action) {
                rewind -> player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                forward -> player.seekTo(player.currentPosition + 30_000)
                captions -> pickTrack(C.TRACK_TYPE_TEXT)
                audio -> pickTrack(C.TRACK_TYPE_AUDIO)
                speed -> pickSpeed()
                else -> super.onActionClicked(action)
            }
        }

        private fun tracksOf(type: Int): List<Pair<Tracks.Group, Int>> {
            val options = ArrayList<Pair<Tracks.Group, Int>>()
            for (group in player.currentTracks.groups) {
                if (group.type != type) continue
                for (i in 0 until group.length) if (group.isTrackSupported(i)) options += group to i
            }
            return options
        }

        private fun trackName(format: MediaFormat, n: Int): String =
            format.label
                ?: format.language?.takeIf { it != C.LANGUAGE_UNDETERMINED }
                    ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).ifEmpty { it } }
                ?: context.getString(R.string.track_number, n + 1)

        /** A list with the current choice ticked; the remote moves through it and OK picks. */
        private fun pickTrack(type: Int) {
            val text = type == C.TRACK_TYPE_TEXT
            val options = tracksOf(type)
            if (options.isEmpty() || (!text && options.size < 2)) {
                toast(context.getString(if (text) R.string.no_subtitles else R.string.one_audio_track))
                return
            }
            val names = options.mapIndexed { n, (group, i) -> trackName(group.getTrackFormat(i), n) }
            val off = text && C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes
            val current = if (off) -1 else options.indexOfFirst { (group, i) -> group.isTrackSelected(i) }
            val items = if (text) listOf(context.getString(R.string.off)) + names else names
            AlertDialog.Builder(context)
                .setTitle(if (text) R.string.subtitles else R.string.audio_track)
                .setSingleChoiceItems(items.toTypedArray(), if (text) current + 1 else current) { dialog, which ->
                    select(type, options.getOrNull(if (text) which - 1 else which))
                    dialog.dismiss()
                }
                .show()
        }

        private fun select(type: Int, option: Pair<Tracks.Group, Int>?) {
            val prefs = TvPrefs(context)
            val profile = store.profileId
            val params = player.trackSelectionParameters.buildUpon()
            if (option == null) {
                player.trackSelectionParameters = params.setTrackTypeDisabled(type, true).build()
                prefs.setSubtitleLanguage(profile, TvPrefs.OFF)
                return
            }
            val (group, index) = option
            val language = group.getTrackFormat(index).language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
            params.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            // The preferred language carries the choice on to the next episode, where this exact track doesn't exist.
            if (type == C.TRACK_TYPE_TEXT) {
                if (language != null) params.setPreferredTextLanguage(language) else params.setSelectUndeterminedTextLanguage(true)
                prefs.setSubtitleLanguage(profile, language ?: TvPrefs.UNDETERMINED)
            } else if (language != null) {
                params.setPreferredAudioLanguage(language)
                prefs.setAudioLanguage(profile, language)
            }
            player.trackSelectionParameters = params.build()
        }

        private fun pickSpeed() {
            val current = SPEEDS.indexOfFirst { kotlin.math.abs(it - player.playbackParameters.speed) < 0.01f }
            val items = SPEEDS.map { if (it == 1f) context.getString(R.string.speed_normal) else speedText(it) }
            AlertDialog.Builder(context)
                .setTitle(R.string.speed)
                .setSingleChoiceItems(items.toTypedArray(), current) { dialog, which ->
                    // Pitch stays the same, so voices sound natural at 1.5×.
                    player.setPlaybackSpeed(SPEEDS[which])
                    dialog.dismiss()
                    label(this)
                }
                .show()
        }

        private fun syncCaptions(on: Boolean) {
            val index = if (on) PlaybackControlsRow.ClosedCaptioningAction.INDEX_ON else PlaybackControlsRow.ClosedCaptioningAction.INDEX_OFF
            if (captions.index == index) return
            captions.index = index
            notifyActionChanged(captions)
        }

        private fun notifyActionChanged(action: Action) {
            val adapter = controlsRow?.secondaryActionsAdapter as? ArrayObjectAdapter ?: return
            val i = adapter.indexOf(action)
            if (i >= 0) adapter.notifyArrayItemRangeChanged(i, 1)
        }

        private fun speedText(value: Float): String =
            context.getString(R.string.speed_value, if (value % 1f == 0f) value.toInt().toString() else value.toString())

        /** The speed button shows the speed itself ("1.5×"), drawn as its icon. */
        private fun speedIcon(value: Float): BitmapDrawable {
            val size = dp(context, 48)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val text = speedText(value)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
                textSize = size * if (text.length > 3) 0.36f else 0.46f
            }
            Canvas(bitmap).drawText(text, size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2, paint)
            return BitmapDrawable(context.resources, bitmap)
        }

        private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}

private const val ACTION_AUDIO = 1001L
private const val ACTION_SPEED = 1002L

/** The speeds offered, on the TV and in the web app. */
private val SPEEDS = floatArrayOf(1f, 1.25f, 1.5f, 2f)
