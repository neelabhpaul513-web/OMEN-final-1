package com.neelabh.omen

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import kotlin.math.max

/** Muted, looping, centre-cropped background video (black wind waves). */
class WindVideoView(c: Context) : TextureView(c), TextureView.SurfaceTextureListener {
    private var mp: MediaPlayer? = null
    private var surface: Surface? = null
    private var vidW = 0
    private var vidH = 0
    var wanted = true
    var onReady: (() -> Unit)? = null
    var onFail: (() -> Unit)? = null

    init { surfaceTextureListener = this; isOpaque = true }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { surface = Surface(st); if (wanted) startPlayer() }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { fit() }
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { release(); surface?.release(); surface = null; return true }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    private fun startPlayer() {
        if (mp != null || surface == null) return
        try {
            val afd = resources.openRawResourceFd(R.raw.omen_wind)
            mp = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length); afd.close()
                setSurface(surface); isLooping = true; setVolume(0f, 0f)
                setOnVideoSizeChangedListener { _, vw, vh -> vidW = vw; vidH = vh; fit() }
                setOnPreparedListener { it.start(); onReady?.invoke() }
                setOnErrorListener { _, _, _ -> this@WindVideoView.release(); onFail?.invoke(); true }
                prepareAsync()
            }
        } catch (e: Exception) { release(); onFail?.invoke() }
    }

    fun playNow() { wanted = true; val p = mp; if (p == null) startPlayer() else try { p.start() } catch (e: Exception) {} }
    fun holdNow() { try { mp?.pause() } catch (e: Exception) {} }
    fun stopForGood() { wanted = false; release() }
    fun release() { try { mp?.release() } catch (e: Exception) {}; mp = null }

    private fun fit() {
        if (vidW == 0 || vidH == 0 || width == 0 || height == 0) return
        val s = max(width.toFloat() / vidW, height.toFloat() / vidH)
        setTransform(Matrix().apply { setScale(vidW * s / width, vidH * s / height, width / 2f, height / 2f) })
    }
}
