package com.parthajb.homereel.tv

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
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
    private var player: ExoPlayer? = null
    private var glue: MovieGlue? = null
    private var subtitleView: SubtitleView? = null
    private var ended = false

    private val tick = object : Runnable {
        override fun run() {
            if (player?.isPlaying == true) saveProgress()
            main.postDelayed(this, 10_000)
        }
    }

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

        exo.setMediaItem(mediaItem(movie), start)
        loadUpcoming()
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = showError(error)

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && !ended) {
                    ended = true
                    saveProgress(atEnd = true)
                    val next = upcoming.firstOrNull()
                    if (next != null) offerNext(next) else activity?.finish()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) saveProgress()
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
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val root = super.onCreateView(inflater, container, savedInstanceState) as ViewGroup
        // Above the video surface, below the controls, which Leanback adds later.
        subtitleView = SubtitleView(requireContext()).apply {
            setUserDefaultStyle()
            setUserDefaultTextSize()
        }
        root.addView(subtitleView, 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }

    override fun onDestroyView() {
        subtitleView = null
        super.onDestroyView()
    }

    override fun onPause() {
        glue?.pause()
        saveProgress()
        super.onPause()
    }

    override fun onDestroy() {
        nextDialog?.dismiss()
        main.removeCallbacks(tick)
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

    /** "Panchayat · S2:E5 Title" for an episode; the title and year for a film. */
    private fun label(glue: MovieGlue) {
        if (movie.kind == Kind.EPISODE) {
            glue.title = movie.showTitle ?: movie.title
            glue.subtitle = "${Format.episodeCode(movie)}  ${movie.title}"
        } else {
            glue.title = movie.title
            glue.subtitle = listOfNotNull(movie.year?.toString(), movie.video?.let { Playability.describe(it.mime) })
                .joinToString(" · ")
        }
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
    private fun offerNext(next: Movie) {
        val context = context ?: return
        val view = LayoutInflater.from(context).inflate(R.layout.next_episode, null)
        val text = view.findViewById<TextView>(R.id.next_text)
        val dialog = android.app.Dialog(context).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(view)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setGravity(android.view.Gravity.BOTTOM or android.view.Gravity.END)
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setOnCancelListener { activity?.finish() }
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
            activity?.finish()
        }
        nextDialog = dialog
        dialog.show()
        view.findViewById<Button>(R.id.next_play).requestFocus()
        main.post(countdown)
    }

    private fun playNext(next: Movie) {
        nextDialog?.dismiss()
        nextDialog = null
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
     * The standard controls plus −10 s / +30 s, a subtitle button that cycles Off → each track, and an audio
     * button that cycles the languages a dubbed film carries.
     */
    private class MovieGlue(context: Context, adapter: LeanbackPlayerAdapter, private val player: ExoPlayer) :
        PlaybackTransportControlGlue<LeanbackPlayerAdapter>(context, adapter) {
        private val rewind = PlaybackControlsRow.RewindAction(context)
        private val forward = PlaybackControlsRow.FastForwardAction(context)
        private val captions = PlaybackControlsRow.ClosedCaptioningAction(context)
        private val audio = Action(ACTION_AUDIO, context.getString(R.string.audio_track), null,
            ContextCompat.getDrawable(context, R.drawable.ic_audio))

        override fun onCreatePrimaryActions(primary: ArrayObjectAdapter) {
            primary.add(rewind)
            super.onCreatePrimaryActions(primary)
            primary.add(forward)
        }

        override fun onCreateSecondaryActions(secondary: ArrayObjectAdapter) {
            secondary.add(captions)
            secondary.add(audio)
        }

        override fun onActionClicked(action: Action) {
            when (action) {
                rewind -> player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                forward -> player.seekTo(player.currentPosition + 30_000)
                captions -> toast(cycle(C.TRACK_TYPE_TEXT, allowOff = true))
                audio -> toast(cycle(C.TRACK_TYPE_AUDIO, allowOff = false))
                else -> super.onActionClicked(action)
            }
        }

        private fun cycle(type: Int, allowOff: Boolean): String {
            val options = ArrayList<Pair<Tracks.Group, Int>>()
            for (group in player.currentTracks.groups) {
                if (group.type != type) continue
                for (i in 0 until group.length) if (group.isTrackSupported(i)) options += group to i
            }
            if (options.isEmpty()) {
                return context.getString(if (type == C.TRACK_TYPE_TEXT) R.string.no_subtitles else R.string.one_audio_track)
            }
            val current = options.indexOfFirst { (g, i) -> g.isTrackSelected(i) }
            val next = current + 1
            val params = player.trackSelectionParameters.buildUpon()
            if (next >= options.size && allowOff) {
                player.trackSelectionParameters = params.setTrackTypeDisabled(type, true).build()
                return context.getString(R.string.subtitles_off)
            }
            val (group, index) = options[next % options.size]
            player.trackSelectionParameters = params
                .setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                .build()
            val format = group.getTrackFormat(index)
            return format.label
                ?: format.language?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).ifEmpty { it } }
                ?: context.getString(R.string.track_number, next % options.size + 1)
        }

        private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

        private companion object {
            const val ACTION_AUDIO = 1001L
        }
    }
}
