package com.example.mpvlibrary.mpv

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.example.mpvlibrary.data.AppLog
import `is`.xyz.mpv.MPVLib

/**
 * Minimal SurfaceView host for libmpv (pattern from mpv-android BaseMPVView).
 * libmpv is process-global singleton: exactly one instance, created by PlayerActivity.
 *
 * Load/attach ordering is load-bearing, two races fixed:
 * 1. loadfile before attachSurface aborts native (WinID==0) → [attached] gates load.
 * 2. surfaceCreated is NOT re-fired for a late addCallback, and our initialize()
 *    runs after async IO — so the callback is registered in init{} and attach is
 *    deferred until BOTH surface and mpv are ready ([tryAttach]).
 */
class MPVPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context), SurfaceHolder.Callback {

    private var pendingFile: String? = null
    private var attached = false
    private var surfaceReady = false
    private var mpvReady = false
    private var voInUse = "gpu"

    init {
        holder.addCallback(this)
    }

    fun initialize(configDir: String, cacheDir: String) {
        runCatching {
            MPVLib.create(context.applicationContext)
            MPVLib.setOptionString("config", "yes")
            MPVLib.setOptionString("config-dir", configDir)
            for (opt in arrayOf("gpu-shader-cache-dir", "icc-cache-dir"))
                MPVLib.setOptionString(opt, cacheDir)
            MPVLib.setOptionString("msg-level", "all=warn")
            MPVLib.init()
            MPVLib.setOptionString("force-window", "no")
            MPVLib.setOptionString("idle", "yes")
            MPVLib.setOptionString("keep-open", "no")
            mpvReady = true
            AppLog.i("mpv", "player initialized (config=$configDir)")
            tryAttach()
        }.onFailure { AppLog.e("mpv", "initialize failed: $it") }
    }

    fun destroy() {
        attached = false
        mpvReady = false
        surfaceReady = false
        pendingFile = null
        runCatching {
            holder.removeCallback(this)
            MPVLib.destroy()
            AppLog.i("mpv", "player destroyed")
        }.onFailure { AppLog.e("mpv", "destroy failed: $it") }
    }

    fun setVo(vo: String) {
        voInUse = vo
        if (!mpvReady) return
        runCatching { MPVLib.setOptionString("vo", vo) }
            .onFailure { AppLog.e("mpv", "setVo($vo) failed: $it") }
    }

    /** Load a file once attachSurface succeeded; otherwise queue. */
    fun playFile(path: String) {
        if (attached) {
            issueLoad(path)
        } else {
            pendingFile = path
            AppLog.i("mpv", "not attached yet — queued load")
        }
    }

    private fun issueLoad(path: String) {
        runCatching {
            MPVLib.command(arrayOf("loadfile", path, "replace"))
            AppLog.i("mpv", "loadfile issued (attached)")
            pendingFile = null
        }.onFailure { AppLog.e("mpv", "loadfile failed: $it") }
    }

    /** Attach when both sides ready; flush queued file. Idempotent. */
    private fun tryAttach() {
        if (attached || !surfaceReady || !mpvReady) return
        AppLog.i("mpv", "attaching surface to mpv")
        val ok = runCatching {
            MPVLib.attachSurface(holder.surface)
            MPVLib.setOptionString("force-window", "yes")
        }.onFailure { AppLog.e("mpv", "attach failed: $it") }.isSuccess
        if (!ok) return
        attached = true
        val file = pendingFile
        if (file != null) {
            issueLoad(file)
        } else {
            runCatching { MPVLib.setPropertyString("vo", voInUse) }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0 || !mpvReady) return
        runCatching { MPVLib.setPropertyString("android-surface-size", "${width}x$height") }
            .onFailure { AppLog.e("mpv", "surface-size failed: $it") }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        AppLog.i("mpv", "surface created")
        surfaceReady = true
        tryAttach()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        AppLog.i("mpv", "surface destroyed")
        surfaceReady = false
        attached = false
        if (!mpvReady) return
        runCatching {
            MPVLib.setPropertyString("vo", "null")
            MPVLib.setPropertyString("force-window", "no")
            MPVLib.detachSurface()
        }.onFailure { AppLog.e("mpv", "detach failed: $it") }
    }
}
