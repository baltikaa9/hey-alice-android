package io.github.serifdrms.heyclaude.wakeword

/**
 * Streaming front end of the openWakeWord pipeline.
 *
 * Every 80 ms chunk of 16 kHz audio becomes one 96-dim speech embedding:
 *   audio -> mel spectrogram (32 bins, 10 ms hop) -> last 76 mel frames -> embedding
 *
 * The window sizes and the mel scaling below have to match what the classifiers were
 * trained on, so they mirror openwakeword/utils.py (AudioFeatures._streaming_features).
 *
 * The two models are passed in as functions so this class has no Android or ONNX
 * dependency and can be unit tested on the JVM.
 */
class FeatureExtractor(
    private val melSpectrogram: (FloatArray) -> Array<FloatArray>,
    private val embedding: (Array<FloatArray>) -> FloatArray,
    private val maxFeatures: Int = 32,
) {
    private val history = FloatArray(MEL_CONTEXT_SAMPLES)
    private var historyLength = 0

    private val melFrames = ArrayDeque<FloatArray>(MEL_WINDOW + 8)
    private val features = ArrayDeque<FloatArray>(maxFeatures + 1)

    init {
        reset()
    }

    /** Number of embeddings currently buffered. */
    val size: Int get() = features.size

    fun reset() {
        historyLength = 0
        melFrames.clear()
        // openWakeWord starts the mel buffer with ones, so the first few embeddings see the
        // same padding the models saw during training.
        repeat(MEL_WINDOW) { melFrames.addLast(FloatArray(MEL_BINS) { 1f }) }
        features.clear()
    }

    /**
     * Feeds exactly one 1280-sample chunk of 16-bit PCM and appends one embedding.
     */
    fun accept(chunk: ShortArray) {
        require(chunk.size == CHUNK_SAMPLES) { "Expected $CHUNK_SAMPLES samples, got ${chunk.size}" }

        // The mel model needs 480 samples (3 hops) of left context to produce frames
        // aligned with the previous chunk.
        val input = FloatArray(historyLength + CHUNK_SAMPLES)
        history.copyInto(input, 0, 0, historyLength)
        for (i in chunk.indices) input[historyLength + i] = chunk[i].toFloat()

        val tailStart = input.size - MEL_CONTEXT_SAMPLES
        input.copyInto(history, 0, tailStart, input.size)
        historyLength = MEL_CONTEXT_SAMPLES

        for (frame in melSpectrogram(input)) {
            require(frame.size == MEL_BINS) { "Unexpected mel frame size ${frame.size}" }
            melFrames.addLast(FloatArray(MEL_BINS) { frame[it] / 10f + 2f })
        }
        while (melFrames.size > MEL_WINDOW) melFrames.removeFirst()

        val window = Array(MEL_WINDOW) { melFrames[it] }
        val vector = embedding(window)
        require(vector.size == EMBEDDING_SIZE) { "Unexpected embedding size ${vector.size}" }

        features.addLast(vector)
        while (features.size > maxFeatures) features.removeFirst()
    }

    /** The last [count] embeddings, oldest first, or null until enough audio has been seen. */
    fun latest(count: Int): Array<FloatArray>? {
        if (features.size < count) return null
        val offset = features.size - count
        return Array(count) { features[offset + it] }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_SAMPLES = 1280
        const val MEL_CONTEXT_SAMPLES = 480
        const val MEL_BINS = 32
        const val MEL_WINDOW = 76
        const val EMBEDDING_SIZE = 96
    }
}
