package com.lilac.anime.desktop

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object MpvPlayer {
    data class State(
        val running: Boolean = false,
        val paused: Boolean = false,
        val position: Double = 0.0,
        val duration: Double = 0.0,
        val speed: Double = 1.0,
        val volume: Double = 100.0
    )

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private var state = State()
    private val alive = AtomicBoolean(false)

    fun addListener(listener: (State) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners -= listener
    }

    fun play(
        url: String,
        subtitlePath: String? = null,
        headers: Map<String, String> = emptyMap()
    ) {
        require(url.isNotBlank()) { "m3u8 URL is empty" }
        stop()

        val command = mutableListOf(
            "mpv",
            "--hwdec=auto",
            "--force-window=yes",
            "--title=LilacAnime",
            "--input-terminal=yes",
            "--term-status-msg=__LILAC__TIME=\${time-pos}__DURATION=\${duration}__PAUSE=\${pause}__SPEED=\${speed}__VOLUME=\${volume}__"
        )

        val httpHeaders = headers
            .filterKeys {
                it.equals("Referer", true) ||
                    it.equals("Origin", true) ||
                    it.equals("User-Agent", true) ||
                    it.equals("Authorization", true) ||
                    it.equals("Cookie", true)
            }
            .map { (key, value) -> "$key: $value" }
            .joinToString(",")

        if (httpHeaders.isNotBlank()) {
            command += "--http-header-fields=$httpHeaders"
        }

        if (!subtitlePath.isNullOrBlank()) {
            command += "--sub-file=$subtitlePath"
        }

        command += url

        val started = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        process = started
        writer = OutputStreamWriter(started.outputStream, StandardCharsets.UTF_8)
        alive.set(true)
        publish(State(running = true))

        thread(isDaemon = true, name = "Lilac-mpv-reader") {
            runCatching {
                BufferedReader(InputStreamReader(started.inputStream, StandardCharsets.UTF_8)).useLines { lines ->
                    lines.forEach { parseStatus(it) }
                }
            }
            alive.set(false)
            publish(state.copy(running = false))
        }
    }

    fun stop() {
        runCatching {
            writer?.write("quit\n")
            writer?.flush()
        }
        writer = null
        process?.takeIf { it.isAlive }?.destroy()
        process = null
        alive.set(false)
        publish(State())
    }

    fun togglePause() = command("cycle pause")
    fun seek(seconds: Double) = command("seek $seconds relative")
    fun setSpeed(value: Double) = command("set speed ${value.coerceIn(0.25, 4.0)}")
    fun setVolume(value: Double) = command("set volume ${value.coerceIn(0.0, 100.0)}")
    fun toggleFullscreen() = command("cycle fullscreen")
    fun toggleSubtitles() = command("cycle sub-visibility")
    fun subtitleDelay(milliseconds: Long) = command("add sub-delay ${milliseconds / 1000.0}")

    fun setSubtitle(path: Path) {
        command("sub-add \"${escape(path.toAbsolutePath().toString())}\"")
    }

    fun clearSubtitles() = command("sub-remove")

    private fun command(value: String) {
        if (!alive.get()) return
        runCatching {
            writer?.write(value)
            writer?.write("\n")
            writer?.flush()
        }
    }

    private fun parseStatus(line: String) {
        if (!line.contains("__LILAC__")) return

        fun number(key: String, fallback: Double): Double =
            Regex("__${key}=([^_]+)__")
                .find(line)
                ?.groupValues
                ?.getOrNull(1)
                ?.toDoubleOrNull()
                ?: fallback

        val paused = Regex("__PAUSE=([^_]+)__")
            .find(line)
            ?.groupValues
            ?.getOrNull(1)
            ?.equals("yes", ignoreCase = true)
            ?: state.paused

        publish(
            state.copy(
                position = number("TIME", state.position).coerceAtLeast(0.0),
                duration = number("DURATION", state.duration).coerceAtLeast(0.0),
                paused = paused,
                speed = number("SPEED", state.speed),
                volume = number("VOLUME", state.volume)
            )
        )
    }

    private fun publish(newState: State) {
        state = newState
        listeners.forEach { listener ->
            runCatching { listener(newState) }
        }
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
