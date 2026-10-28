package io.github.serifdrms.heyclaude.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WakeWordDetectorTest {

    private val extractor = FeatureExtractor(
        melSpectrogram = { samples -> Array(samples.size / 160 - 3) { FloatArray(FeatureExtractor.MEL_BINS) } },
        embedding = { FloatArray(FeatureExtractor.EMBEDDING_SIZE) },
    )
    private val chunk = ShortArray(FeatureExtractor.CHUNK_SAMPLES)

    /** Classifier that replays a fixed list of scores, one per call. */
    private fun scripted(id: String, vararg scores: Float): WakeWord {
        val queue = ArrayDeque(scores.toList())
        return WakeWord(id, inputFrames = 16) { queue.removeFirstOrNull() ?: 0f }
    }

    private fun warmUp(detector: WakeWordDetector) {
        // 15 chunks fill the feature window; the 16th is the first that gets scored.
        repeat(15) { assertNull(detector.process(chunk)) }
    }

    @Test
    fun singleSpikeIsIgnored() {
        val detector = WakeWordDetector(extractor, listOf(scripted("a", 0.9f, 0.1f, 0.9f, 0.1f)), patience = 2)
        warmUp(detector)
        repeat(4) { assertNull(detector.process(chunk)) }
    }

    @Test
    fun firesAfterConsecutiveHits() {
        val detector = WakeWordDetector(extractor, listOf(scripted("a", 0.2f, 0.7f, 0.8f)), patience = 2)
        warmUp(detector)
        assertNull(detector.process(chunk))
        assertNull(detector.process(chunk))
        val hit = detector.process(chunk)
        assertNotNull(hit)
        assertEquals("a", hit!!.wakeWordId)
        assertEquals(0.8f, hit.score, 0f)
    }

    @Test
    fun streakRestartsAfterDetection() {
        val detector = WakeWordDetector(extractor, listOf(scripted("a", 0.9f, 0.9f, 0.9f, 0.9f)), patience = 2)
        warmUp(detector)
        assertNull(detector.process(chunk))
        assertNotNull(detector.process(chunk))
        assertNull(detector.process(chunk))
        assertNotNull(detector.process(chunk))
    }

    @Test
    fun thresholdIsRespected() {
        val detector = WakeWordDetector(
            extractor, listOf(scripted("a", 0.6f, 0.6f)), threshold = 0.7f, patience = 2,
        )
        warmUp(detector)
        assertNull(detector.process(chunk))
        assertNull(detector.process(chunk))
        assertEquals(0.6f, detector.lastScore, 0f)
    }

    @Test
    fun picksTheStrongerOfTwoModels() {
        val detector = WakeWordDetector(
            extractor,
            listOf(scripted("a", 0.6f, 0.6f), scripted("b", 0.8f, 0.95f)),
            patience = 2,
        )
        warmUp(detector)
        assertNull(detector.process(chunk))
        assertEquals("b", detector.process(chunk)?.wakeWordId)
    }
}
