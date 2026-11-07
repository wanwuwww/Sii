// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.sii.browser.R
import com.sii.browser.core.webview.SniffType
import com.sii.browser.core.webview.SniffedResource
import com.sii.browser.download.DownloadService
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 内置原生播放页：ExoPlayer/Media3 直接播放嗅探到的 mp4/m3u8，
 * 带当前页面的 Referer/UA/Cookie，解决 WebView HTML5 播不动的问题。
 * 传入的是当前页可播放资源列表，标题栏箭头切换；左/右竖直滑调亮度/音量，横向滑拉进度。
 */
class PlayerFragment : Fragment() {

    private var player: ExoPlayer? = null
    private var items: List<SniffedResource> = emptyList()
    private var position: Int = 0
    private var referer: String? = null
    private var userAgent: String? = null

    private var dim: View? = null
    private var osd: View? = null
    private var osdText: TextView? = null
    private var osdBar: android.widget.ProgressBar? = null
    private var topBar: View? = null
    private var playerViewRef: PlayerView? = null
    private var playPause: ImageButton? = null
    private var controllerPrepared = false
    private var speedButton: TextView? = null
    private var audioButton: TextView? = null
    private var listPopup: PopupWindow? = null
    private lateinit var audio: AudioManager

    /** 画面亮度 0.02..1，通过黑色遮罩实现，不改系统亮度。 */
    private var brightness = 1f
    private var verticalStart = 1f
    private var volumeStart = 0
    private var seekStart = 0L
    private var seekDuration = 0L
    private var seekTarget = -1L

    private var controlsBeforeGesture = false

    private val hideOsd = Runnable { osd?.visibility = View.GONE }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_player, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        items = decode(arguments?.getString(ARG_ITEMS))
        position = (arguments?.getInt(ARG_INDEX) ?: 0).coerceIn(0, (items.size - 1).coerceAtLeast(0))
        referer = arguments?.getString(ARG_REFERER)
        userAgent = arguments?.getString(ARG_UA)

        dim = view.findViewById(R.id.player_dim)
        osd = view.findViewById<View>(R.id.player_osd).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0x4D000000)
            }
        }
        osdText = view.findViewById(R.id.player_osd_text)
        osdBar = view.findViewById(R.id.player_osd_bar)
        audio = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val playerView = view.findViewById<PlayerView>(R.id.player_view)
        playerViewRef = playerView
        // 控制器不要跟着触摸自己切显隐，改由手势层的单击控制，拖动进度时不会一上一下。
        playerView.setControllerHideOnTouch(false)
        playerView.setControllerShowTimeoutMs(3000)
        topBar = view.findViewById(R.id.player_top)
        val player = ExoPlayer.Builder(requireContext()).build()
        this.player = player
        playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    requireContext(),
                    R.string.player_play_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
            override fun onPlaybackParametersChanged(params: PlaybackParameters) {
                updateSpeedButton()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playPause?.setImageResource(
                    if (isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play
                )
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                updateAudioButton(tracks)
            }
        })

        val gestureLayout = view as PlayerGestureLayout
        gestureLayout.excluded = listOfNotNull(
            topBar,
            view.findViewById(androidx.media3.ui.R.id.exo_bottom_bar)
        )
        gestureLayout.listener = object : PlayerGestureLayout.Listener {
            override fun onVerticalStart(side: PlayerGestureLayout.Side) {
                beginGesture()
                if (side == PlayerGestureLayout.Side.RIGHT) {
                    volumeStart = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                } else {
                    verticalStart = brightness
                }
            }

            override fun onVerticalMove(side: PlayerGestureLayout.Side, delta: Float) {
                if (side == PlayerGestureLayout.Side.RIGHT) {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    val target = (volumeStart + delta * max).roundToInt().coerceIn(0, max)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                    showOsd(getString(R.string.player_volume, target * 100 / max))
                } else {
                    brightness = (verticalStart + delta).coerceIn(0.02f, 1f)
                    dim?.alpha = (1f - brightness) * 0.75f
                    showOsd(getString(R.string.player_brightness, (brightness * 100).roundToInt()))
                }
            }

            override fun onVerticalEnd() {
                endGesture()
                scheduleHideOsd()
            }

            override fun onSeekStart() {
                beginGesture()
                val p = player
                seekStart = p?.currentPosition ?: 0L
                seekDuration = p?.duration?.coerceAtLeast(0L) ?: 0L
                seekTarget = -1L
            }

            override fun onSeekMove(fraction: Float) {
                if (seekDuration <= 0L) return
                val target = (seekStart + fraction * SEEK_SPAN_MS).toLong()
                    .coerceIn(0L, seekDuration)
                seekTarget = target
                showOsdProgress(
                    formatTime(target) + " / " + formatTime(seekDuration),
                    target.toFloat() / seekDuration
                )
            }

            override fun onSeekEnd() {
                val target = seekTarget
                seekTarget = -1L
                if (target >= 0L) player.seekTo(target)
                endGesture()
                scheduleHideOsd()
            }

            override fun onTap() {
                val pv = playerViewRef ?: return
                if (pv.isControllerFullyVisible) pv.hideController() else pv.showController()
            }
        }

        view.findViewById<View>(R.id.player_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<View>(R.id.player_prev).setOnClickListener {
            if (position > 0) load(position - 1)
        }
        view.findViewById<View>(R.id.player_next).setOnClickListener {
            if (position < items.size - 1) load(position + 1)
        }
        view.findViewById<View>(R.id.player_download).setOnClickListener { download() }
        view.findViewById<View>(R.id.player_rotate).setOnClickListener { toggleOrientation() }
        // 顶部条跟随媒体控制器一起显隐，避免点击画面后上面那条一直杵着。
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                topBar?.visibility = visibility
                if (visibility == View.VISIBLE) prepareController(playerView)
            }
        )
        prepareController(playerView)
        applyImmersive()
        load(position)

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    parentFragmentManager.popBackStack()
                }
            }
        )
    }

    /** 切换播放项：Cookie/Referer 按该资源自身计算，否则防盗链站会 403。 */
    private fun load(index: Int) {
        val res = items.getOrNull(index) ?: return
        val player = player ?: return
        position = index
        val mediaItem = MediaItem.Builder()
            .setUri(res.url)
            .setMimeType(if (res.type == SniffType.HLS) MimeTypes.APPLICATION_M3U8 else null)
            .build()
        player.setMediaSource(
            DefaultMediaSourceFactory(httpDataSourceFactory(res)).createMediaSource(mediaItem)
        )
        // 新视频的音轨表不一样，上一部的强制音轨不能沿用。
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_AUDIO)
            .build()
        player.prepare()
        player.playWhenReady = true

        view?.findViewById<TextView>(R.id.player_title)?.text = fileName(res.url)
        view?.findViewById<TextView>(R.id.player_count)?.apply {
            visibility = if (items.size > 1) View.VISIBLE else View.GONE
            text = "${index + 1} / ${items.size}"
        }
        val prev = view?.findViewById<View>(R.id.player_prev)
        val next = view?.findViewById<View>(R.id.player_next)
        if (items.size > 1) {
            prev?.visibility = View.VISIBLE
            next?.visibility = View.VISIBLE
            prev?.alpha = if (index > 0) 1f else 0.3f
            next?.alpha = if (index < items.size - 1) 1f else 0.3f
        } else {
            prev?.visibility = View.GONE
            next?.visibility = View.GONE
        }
    }

    private fun showOsd(text: String) {
        val view = view ?: return
        view.removeCallbacks(hideOsd)
        osdText?.text = text
        osdBar?.visibility = View.GONE
        osd?.visibility = View.VISIBLE
    }

    /** 带进度条的提示：横滑/双击跳转时用，[ratio] 为目标位置占比。 */
    private fun showOsdProgress(text: String, ratio: Float) {
        val view = view ?: return
        view.removeCallbacks(hideOsd)
        osdText?.text = text
        osdBar?.apply {
            progress = (ratio.coerceIn(0f, 1f) * 1000).toInt()
            visibility = View.VISIBLE
        }
        osd?.visibility = View.VISIBLE
    }

    private fun scheduleHideOsd() {
        val view = view ?: return
        view.removeCallbacks(hideOsd)
        view.postDelayed(hideOsd, 700L)
    }

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%02d:%02d".format(minutes, seconds)
    }

    /**
     * 默认控制器的两处修正：去掉全屏 60% 黑遮罩（点一下不再变暗），
     * 把正中间的播放/快进按钮移到底部条里，不再挡住画面。
     */
    /** 控制器可能懒加载：等它存在后再替换控件，避免空操作。 */
    private fun prepareController(playerView: PlayerView) {
        if (controllerPrepared) return
        if (playerView.findViewById<View>(androidx.media3.ui.R.id.exo_center_controls) == null) return
        controllerPrepared = true
        tuneController(playerView)
        addSpeedAndAudioButtons(playerView)
    }

    private fun tuneController(playerView: PlayerView) {
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_controls_background)
            ?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        val center = playerView.findViewById<ViewGroup>(
            androidx.media3.ui.R.id.exo_center_controls
        )
        val bottom = playerView.findViewById<FrameLayout>(
            androidx.media3.ui.R.id.exo_bottom_bar
        )
        val parent = center?.parent as? ViewGroup
        if (center != null && bottom != null && parent != null) {
            // 整组换成自绘控件：Media3 的 include 按钮无法稳定把数字放到圈心。
            (center.parent as? ViewGroup)?.removeView(center)
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
            }
            row.addView(buildSeekButton(5, forward = false) { seekBy(-5_000L) })
            val play = ImageButton(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
                setImageResource(R.drawable.ic_player_play)
                imageTintList = android.content.res.ColorStateList.valueOf(Color.BLACK)
                contentDescription = getString(R.string.player_play)
                val pad = dp(10)
                setPadding(pad, pad, pad, pad)
                setOnClickListener { togglePlay() }
            }
            playPause = play
            row.addView(play)
            row.addView(buildSeekButton(15, forward = true) { seekBy(15_000L) })
            bottom.addView(
                row,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.CENTER_HORIZONTAL
                )
            )
        }
        // 时间字号与内边距调小，给居中的按钮让出位置。
        playerView.findViewById<TextView>(androidx.media3.ui.R.id.exo_position)
            ?.setTextSize(12f)
        playerView.findViewById<TextView>(androidx.media3.ui.R.id.exo_duration)
            ?.setTextSize(12f)
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_time)
            ?.setPadding(dp(4), 0, dp(4), 0)
    }

    /** 把齿轮里的「倍速 / 音轨」移成两个按钮；齿轮没有内容可放，直接移除。 */
    private fun addSpeedAndAudioButtons(playerView: PlayerView) {
        val basic = playerView.findViewById<LinearLayout>(
            androidx.media3.ui.R.id.exo_basic_controls
        ) ?: return
        basic.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.let { gear ->
            (gear.parent as? ViewGroup)?.removeView(gear)
        }
        val speed = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            gravity = android.view.Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            setTextSize(15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { showSpeedPopup() }
        }
        val track = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            text = getString(R.string.player_audio)
            visibility = View.GONE
            gravity = android.view.Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            setTextSize(14f)
            setTextColor(Color.WHITE)
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { showAudioPopup() }
        }
        basic.addView(speed)
        basic.addView(track)
        speedButton = speed
        audioButton = track
        updateSpeedButton()
    }

    private fun updateSpeedButton() {
        speedButton?.text = speedLabel(player?.playbackParameters?.speed ?: 1f)
    }

    private fun updateAudioButton(tracks: androidx.media3.common.Tracks) {
        val count = tracks.groups
            .filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
            .sumOf { it.length }
        audioButton?.visibility = if (count > 1) View.VISIBLE else View.GONE
    }

    private fun speedLabel(speed: Float): String {
        val hundredths = (speed * 100).roundToInt()
        return if (hundredths % 10 == 0) "%.1fx".format(speed) else "%.2fx".format(speed)
    }

    /** 一层浮层，样式贴近 Media3 原来的齿轮弹层：深色圆角面板、当前项高亮。 */
    private fun showListPopup(
        anchor: View,
        title: String,
        items: List<String>,
        selected: Int,
        onPick: (Int) -> Unit
    ) {
        val context = requireContext()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xF0202020.toInt())
            }
        }
        panel.addView(TextView(context).apply {
            text = title
            setTextSize(12f)
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(dp(12), dp(4), dp(12), dp(6))
        })
        val accent = ThemeAccent.color(context)
        items.forEachIndexed { index, label ->
            panel.addView(TextView(context).apply {
                text = label
                setTextSize(14.5f)
                setTextColor(if (index == selected) accent else Color.WHITE)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener {
                    onPick(index)
                    listPopup?.dismiss()
                }
            })
        }
        val width = dp(170)
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popup = PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(10).toFloat()
        }
        listPopup?.dismiss()
        listPopup = popup
        popup.showAsDropDown(
            anchor,
            -(width - anchor.width),
            -(panel.measuredHeight + anchor.height + dp(6))
        )
    }

    private fun showSpeedPopup() {
        val p = player ?: return
        val anchor = speedButton ?: return
        val current = p.playbackParameters.speed
        val selected = SPEEDS.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
            .coerceAtLeast(0)
        showListPopup(
            anchor,
            getString(R.string.player_speed),
            SPEEDS.map { speedLabel(it) },
            selected
        ) { which ->
            p.setPlaybackSpeed(SPEEDS[which])
            updateSpeedButton()
        }
    }

    private fun showAudioPopup() {
        val p = player ?: return
        val anchor = audioButton ?: return
        val labels = mutableListOf(getString(R.string.player_audio_auto))
        val tracks = mutableListOf<Pair<androidx.media3.common.Tracks.Group, Int>>()
        var selected = 0
        p.currentTracks.groups
            .filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
            .forEach { group ->
                for (i in 0 until group.length) {
                    tracks.add(group to i)
                    val format = group.getTrackFormat(i)
                    labels.add(
                        format.label?.takeIf { it.isNotBlank() }
                            ?: format.language?.takeIf { it.isNotBlank() && it != "und" }
                            ?: getString(R.string.player_audio_track, tracks.size)
                    )
                    if (group.isTrackSelected(i)) selected = tracks.size
                }
            }
        showListPopup(
            anchor,
            getString(R.string.player_audio),
            labels,
            selected
        ) { which ->
            if (which == 0) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_AUDIO)
                    .build()
            } else {
                val (group, index) = tracks[which - 1]
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setOverrideForType(
                        androidx.media3.common.TrackSelectionOverride(
                            group.mediaTrackGroup,
                            index
                        )
                    )
                    .build()
            }
        }
    }

    private fun buildSeekButton(seconds: Int, forward: Boolean, onClick: () -> Unit): View =
        SeekButtonView(requireContext()).apply {
            this.seconds = seconds
            this.forward = forward
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { onClick() }
        }

    private fun togglePlay() {
        val p = player ?: return
        if (p.playWhenReady) p.pause() else p.play()
    }

    /** 手势期间冻结控制条自动隐藏，松手后恢复原状态。 */
    private fun beginGesture() {
        val pv = playerViewRef ?: return
        controlsBeforeGesture = pv.isControllerFullyVisible
        if (controlsBeforeGesture) pv.setControllerShowTimeoutMs(0)
    }

    private fun endGesture() {
        val pv = playerViewRef ?: return
        if (controlsBeforeGesture) {
            pv.setControllerShowTimeoutMs(3000)
            pv.showController()
        }
        controlsBeforeGesture = false
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val duration = p.duration
        if (duration <= 0L) return
        val target = (p.currentPosition + deltaMs).coerceIn(0L, duration)
        p.seekTo(target)
        showOsdProgress(
            formatTime(target) + " / " + formatTime(duration),
            target.toFloat() / duration
        )
        scheduleHideOsd()
    }

    private fun toggleOrientation() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requireActivity().requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    /** 横屏时隐藏系统栏，竖屏恢复；方向按钮与设备旋转都走这里。 */
    private fun applyImmersive() {
        val window = activity?.window ?: return
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyImmersive()
    }

    private fun httpDataSourceFactory(res: SniffedResource): DefaultHttpDataSource.Factory {
        val cookie = runCatching {
            android.webkit.CookieManager.getInstance().getCookie(res.url)
        }.getOrNull()
        val headers = mutableMapOf<String, String>()
        (res.pageUrl ?: referer)?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
        cookie?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
        return DefaultHttpDataSource.Factory()
            .setUserAgent(
                userAgent ?: android.webkit.WebSettings.getDefaultUserAgent(requireContext())
            )
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
    }

    private fun download() {
        val context = context ?: return
        val res = items.getOrNull(position) ?: return
        if (res.type == SniffType.HLS) {
            DownloadService.startHlsDownload(context, res.url, null, res.pageUrl ?: referer, userAgent)
        } else {
            DownloadService.startDownload(context, res.url, null, null)
        }
        Toast.makeText(context, R.string.sniff_download_started, Toast.LENGTH_SHORT).show()
    }

    private fun fileName(url: String): String =
        runCatching { Uri.parse(url).lastPathSegment }
            .getOrNull().orEmpty().ifEmpty { url }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        player?.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        player?.playWhenReady = false
    }

    override fun onDestroyView() {
        view?.removeCallbacks(hideOsd)
        view?.findViewById<PlayerView>(R.id.player_view)?.player = null
        player?.release()
        player = null
        dim = null
        osd = null
        osdText = null
        osdBar = null
        playPause = null
        playerViewRef = null
        controllerPrepared = false
        topBar = null
        speedButton = null
        audioButton = null
        listPopup?.dismiss()
        listPopup = null
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
        (activity as? ShellActivity)?.applyOrientation()
        super.onDestroyView()
    }

    companion object {
        private const val ARG_ITEMS = "items"
        private const val ARG_INDEX = "index"
        private const val ARG_REFERER = "referer"
        private const val ARG_UA = "ua"

        /** 横向滑满屏对应的进度跨度。 */
        private const val SEEK_SPAN_MS = 90_000f

        /** 倍速列表，最后一档 3.0 是 Media3 自带菜单没有的。 */
        private val SPEEDS = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)

        fun newInstance(
            items: List<SniffedResource>,
            index: Int,
            referer: String?,
            userAgent: String?
        ): PlayerFragment = PlayerFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_ITEMS, encode(items))
                putInt(ARG_INDEX, index)
                putString(ARG_REFERER, referer)
                putString(ARG_UA, userAgent)
            }
        }

        private fun encode(items: List<SniffedResource>): String {
            val array = JSONArray()
            items.forEach {
                array.put(
                    JSONObject()
                        .put("u", it.url)
                        .put("t", it.type.name)
                        .put("p", it.pageUrl.orEmpty())
                )
            }
            return array.toString()
        }

        private fun decode(raw: String?): List<SniffedResource> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { i ->
                    val obj = array.optJSONObject(i) ?: return@mapNotNull null
                    val url = obj.optString("u")
                    if (url.isBlank()) return@mapNotNull null
                    SniffedResource(
                        url = url,
                        type = runCatching { SniffType.valueOf(obj.optString("t")) }
                            .getOrDefault(SniffType.VIDEO),
                        pageUrl = obj.optString("p").ifBlank { null }
                    )
                }
            }.getOrDefault(emptyList())
        }
    }
}
