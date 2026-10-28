package io.github.serifdrms.heyclaude.wakeword

/**
 * A wake word classifier: takes the last [inputFrames] embeddings and returns a score in 0..1.
 */
class WakeWord(
    val id: String,
    val inputFrames: Int,
    private val classify: (Array<FloatArray>) -> Float,
) {
    fun score(features: Array<FloatArray>): Float = classify(features)
}

data class Detection(val wakeWordId: String, val score: Float)

/**
 * Runs every wake word on each chunk and reports a hit when a model stays above the
 * threshold for [patience] consecutive chunks. Requiring more than one chunk filters
 * out most single-frame spikes from TV or music.
 */
class WakeWordDetector(
    private val extractor: FeatureExtractor,
    private val wakeWords: List<WakeWord>,
    @Volatile var threshold: Float = DEFAULT_THRESHOLD,
    private val patience: Int = DEFAULT_PATIENCE,
) {
    private val streaks = IntArray(wakeWords.size)

    /** Highest score of the last processed chunk, for the level meter in the UI. */
    var lastScore: Float = 0f
        private set

    init {
        require(wakeWords.isNotEmpty()) { "At least one wake word model is required" }
        require(patience >= 1)
    }

    fun reset() {
        extractor.reset()
        streaks.fill(0)
        lastScore = 0f
    }

    fun process(chunk: ShortArray): Detection? {
        extractor.accept(chunk)

        var best: Detection? = null
        var top = 0f
        for ((index, wakeWord) in wakeWords.withIndex()) {
            val window = extractor.latest(wakeWord.inputFrames) ?: continue
            val score = wakeWord.score(window)
            if (score > top) top = score

            streaks[index] = if (score >= threshold) streaks[index] + 1 else 0
            if (streaks[index] >= patience && (best == null || score > best.score)) {
                best = Detection(wakeWord.id, score)
            }
        }
        lastScore = top

        if (best != null) streaks.fill(0)
        return best
    }

    companion object {
        const val DEFAULT_THRESHOLD = 0.5f
        const val DEFAULT_PATIENCE = 2
    }
}
