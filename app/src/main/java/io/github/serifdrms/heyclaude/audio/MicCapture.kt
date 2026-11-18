package io.github.serifdrms.heyclaude.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import io.github.serifdrms.heyclaude.wakeword.FeatureExtractor
import java.util.concurrent.Executors

/**
 * Reads 16 kHz mono PCM from the microphone in 80 ms chunks on its own thread.
 *
 * Audio never leaves this class except as the chunk handed to [onChunk]; nothing is written
 * to disk and the buffer is reused, so at most one chunk is ever kept in memory.
 *
 * VOICE_RECOGNITION is deliberately not a privacy-sensitive source. If this app opened the
 * mic as VOICE_COMMUNICATION, Android would give it priority and Alice would hear silence.
 */
class MicCapture(
    private val context: Context,
    private val onChunk: (ShortArray) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var silenced = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private val callbackExecutor = Executors.newSingleThreadExecutor()

    private val silenceCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            silenced = record?.activeRecordingConfiguration?.isClientSilenced ?: false
        }
    }

    val isRunning: Boolean get() = running

    /** Session of the last recording we opened; kept after stop so our own entry can be told apart. */
    @Volatile var sessionId: Int = -1
        private set

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start() {
        if (running) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError(SecurityException("RECORD_AUDIO not granted"))
            return
        }

        val format = AudioFormat.Builder()
            .setSampleRate(FeatureExtractor.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            FeatureExtractor.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val builder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer, FeatureExtractor.CHUNK_SAMPLES * 2 * 4))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) builder.setPrivacySensitive(false)

        val audioRecord = try {
            builder.build()
        } catch (e: Exception) {
            onError(e)
            return
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            onError(IllegalStateException("AudioRecord failed to initialize"))
            return
        }

        audioRecord.registerAudioRecordingCallback(callbackExecutor, silenceCallback)
        try {
            audioRecord.startRecording()
        } catch (e: Exception) {
            audioRecord.unregisterAudioRecordingCallback(silenceCallback)
            audioRecord.release()
            onError(e)
            return
        }

        record = audioRecord
        sessionId = audioRecord.audioSessionId
        silenced = false
        running = true
        thread = Thread({ readLoop(audioRecord) }, "mic-capture").apply { start() }
    }

    @Synchronized
    fun stop() {
        if (record == null) return
        running = false
        record?.let {
            try {
                it.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "stop() on an inactive recorder", e)
            }
        }
        thread?.join()
        thread = null
        record?.unregisterAudioRecordingCallback(silenceCallback)
        record?.release()
        record = null
    }

    fun shutdown() {
        stop()
        callbackExecutor.shutdown()
    }

    private fun readLoop(audioRecord: AudioRecord) {
        val chunk = ShortArray(FeatureExtractor.CHUNK_SAMPLES)
        var chunksSinceCheck = 0
        try {
            while (running) {
                var filled = 0
                while (filled < chunk.size && running) {
                    val n = audioRecord.read(chunk, filled, chunk.size - filled)
                    if (n < 0) throw IllegalStateException("AudioRecord.read returned $n")
                    filled += n
                }
                if (!running) break

                // The callback only fires on changes, so re-check now and then.
                if (++chunksSinceCheck >= 25) {
                    chunksSinceCheck = 0
                    silenced = audioRecord.activeRecordingConfiguration?.isClientSilenced ?: false
                }
                // Another app (e.g. Alice in voice mode) owns the mic; skip the work.
                if (silenced) continue

                onChunk(chunk)
            }
        } catch (e: Throwable) {
            if (running) {
                running = false
                onError(e)
            }
        }
    }

    private companion object {
        const val TAG = "MicCapture"
    }
}
