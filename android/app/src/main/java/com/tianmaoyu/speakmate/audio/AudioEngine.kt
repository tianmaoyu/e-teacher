package com.tianmaoyu.speakmate.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.tianmaoyu.speakmate.core.Const
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 音频引擎：麦克风采集 + 流式播放。
 *
 * 几个必须做对的地方：
 *   1. 采样率按设备能力协商（24kHz 优先，回落 16kHz）。两端必须一致，否则会变速变调。
 *   2. 音源用 VOICE_COMMUNICATION 并开启 AEC / NS，否则外放时模型会听到自己的声音
 *      并不断自我打断 —— 这是全双工语音 App 最常见的翻车点。
 *   3. PCM16 必须按整样本对齐，上行帧字节数一定是偶数。
 *   4. 播放队列要设上限，慢网络下宁可丢旧帧也不能让延迟无限累积。
 */
class AudioEngine(
    private val context: Context,
    private val onFrame: (ByteArray) -> Unit,
    private val onMicLevel: (Float) -> Unit,
    private val onPlaybackLevel: (Float) -> Unit,
) {

    @Volatile
    var sampleRate: Int = 24000
        private set

    @Volatile
    var muted: Boolean = false

    @Volatile
    private var running = false

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var readThread: Thread? = null
    private var writeThread: Thread? = null

    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null

    private val queue = LinkedBlockingQueue<ByteArray>()
    private val queuedBytes = AtomicInteger(0)
    private val maxQueueBytes: Int
        get() = sampleRate * 2 * Const.MAX_PLAYBACK_QUEUE_MS / 1000

    @Volatile
    private var lastWriteAtMs = 0L

    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /** 协商采样率并启动收发。返回实际使用的采样率；失败返回 0。 */
    @SuppressLint("MissingPermission")
    fun start(preferSpeaker: Boolean = true): Int {
        if (running) return sampleRate

        val rate = negotiateRate()
        if (rate == 0) {
            Log.e(TAG, "设备不支持 24kHz / 16kHz 单声道录音")
            return 0
        }
        sampleRate = rate

        if (!startRecord(rate)) return 0
        if (!startPlayer(rate)) {
            stopRecord()
            return 0
        }

        running = true
        applyAudioRouting(preferSpeaker)

        readThread = Thread({ readLoop() }, "speakmate-mic").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        writeThread = Thread({ writeLoop() }, "speakmate-spk").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return rate
    }

    fun stop() {
        running = false
        readThread?.join(500)
        writeThread?.join(500)
        readThread = null
        writeThread = null
        queue.clear()
        queuedBytes.set(0)
        stopRecord()
        stopPlayer()
        releaseEffects()
        restoreAudioRouting()
    }

    /** 台词音频入队。超过队列上限时丢弃最旧的帧，保证低延迟。 */
    fun enqueue(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        queue.offer(pcm)
        var total = queuedBytes.addAndGet(pcm.size)
        while (total > maxQueueBytes) {
            val dropped = queue.poll() ?: break
            total = queuedBytes.addAndGet(-dropped.size)
            Log.w(TAG, "播放队列积压，丢弃 ${dropped.size} 字节")
        }
    }

    /** 模型是否仍在说话 —— 由播放队列推导，官方没有「说完」事件。 */
    fun isSpeaking(): Boolean {
        if (queuedBytes.get() > 0) return true
        val since = SystemClock.elapsedRealtime() - lastWriteAtMs
        return lastWriteAtMs > 0 && since < 250
    }

    fun bufferedBytes(): Int = queuedBytes.get()

    /** 用户按下静音时清空未播完的音频，避免"静音了还在响"。 */
    fun flushPlayback() {
        queue.clear()
        queuedBytes.set(0)
    }

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    private fun negotiateRate(): Int {
        for (rate in intArrayOf(24000, 16000)) {
            val min = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (min > 0) return rate
        }
        return 0
    }

    @SuppressLint("MissingPermission")
    private fun startRecord(rate: Int): Boolean {
        val frameBytes = rate * 2 * Const.FRAME_MS / 1000
        val minBuf = AudioRecord.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufSize = max(minBuf * 2, frameBytes * 4)

        for (source in intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
        )) {
            val r = try {
                AudioRecord(
                    source,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "AudioRecord($source) 构造失败：${t.message}")
                null
            }
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                record = r
                enableEffects(r.audioSessionId)
                try {
                    r.startRecording()
                } catch (t: Throwable) {
                    Log.e(TAG, "startRecording 失败：${t.message}")
                    r.release()
                    record = null
                    continue
                }
                Log.i(TAG, "录音已启动 source=$source rate=$rate")
                return true
            }
            r?.release()
        }
        return false
    }

    private fun startPlayer(rate: Int): Boolean {
        val minOut = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufSize = max(minOut * 2, rate * 2 * Const.PLAYBACK_PREBUFFER_MS / 1000 * 2)
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Throwable) {
            Log.e(TAG, "AudioTrack 构造失败：${e.message}")
            return false
        }
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            return false
        }
        track = t
        t.play()
        return true
    }

    private fun enableEffects(sessionId: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) return
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }
            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
            }
        }.onFailure { Log.w(TAG, "音频效果器启用失败：${it.message}") }
        Log.i(
            TAG,
            "AEC=${echoCanceler != null} NS=${noiseSuppressor != null} AGC=${gainControl != null}",
        )
    }

    private fun releaseEffects() {
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        runCatching { gainControl?.release() }
        echoCanceler = null
        noiseSuppressor = null
        gainControl = null
    }

    private fun applyAudioRouting(preferSpeaker: Boolean) {
        val am = audioManager ?: return
        runCatching {
            @Suppress("DEPRECATION")
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = preferSpeaker
        }
    }

    private fun restoreAudioRouting() {
        val am = audioManager ?: return
        runCatching {
            am.isSpeakerphoneOn = false
            @Suppress("DEPRECATION")
            am.mode = AudioManager.MODE_NORMAL
        }
    }

    // ------------------------------------------------------------------
    // 收发线程
    // ------------------------------------------------------------------

    private fun readLoop() {
        val frameBytes = sampleRate * 2 * Const.FRAME_MS / 1000
        val buffer = ByteArray(frameBytes)
        val r = record ?: return
        while (running) {
            val n = try {
                r.read(buffer, 0, buffer.size)
            } catch (t: Throwable) {
                Log.e(TAG, "read 异常：${t.message}")
                break
            }
            if (n <= 0) continue
            // PCM16 必须整样本对齐：丢掉可能多出来的那一个字节
            val even = n - (n % 2)
            if (even <= 0) continue
            onMicLevel(rms(buffer, even))
            if (!muted) {
                onFrame(buffer.copyOf(even))
            }
        }
    }

    private fun writeLoop() {
        val t = track ?: return
        while (running) {
            val chunk = try {
                queue.poll(4, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            if (chunk == null) continue
            try {
                t.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
            } catch (t3: Throwable) {
                Log.e(TAG, "write 异常：${t3.message}")
                break
            }
            queuedBytes.addAndGet(-chunk.size)
            lastWriteAtMs = SystemClock.elapsedRealtime()
            onPlaybackLevel(rms(chunk, chunk.size))
        }
    }

    private fun stopRecord() {
        val r = record ?: return
        record = null
        runCatching { if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop() }
        runCatching { r.release() }
    }

    private fun stopPlayer() {
        val t = track ?: return
        track = null
        runCatching { if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop() }
        runCatching { t.release() }
    }

    private companion object {
        const val TAG = "SpeakMateAudio"

        fun rms(pcm: ByteArray, length: Int): Float {
            if (length < 2) return 0f
            var sum = 0.0
            var i = 0
            var count = 0
            while (i + 1 < length) {
                val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
                val s = sample.toShort().toInt()
                sum += (s.toDouble() * s.toDouble())
                i += 2
                count++
            }
            if (count == 0) return 0f
            val rms = sqrt(sum / count)
            // 映射到 0..1，语音场景下 3000 的 RMS 已经算很响
            return min(1f, (rms / 3000.0).toFloat().let { max(it, 0f) })
        }
    }
}
