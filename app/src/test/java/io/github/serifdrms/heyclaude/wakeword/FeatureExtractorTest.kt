package io.github.serifdrms.heyclaude.wakeword

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeatureExtractorTest {

    private val melInputs = mutableListOf<FloatArray>()
    private val embeddingInputs = mutableListOf<Array<FloatArray>>()

    /** Fake mel model: one frame per 160 samples minus 3, every bin set to the frame's first sample. */
    private val fakeMel: (FloatArray) -> Array<FloatArray> = { samples ->
        melInputs += samples.copyOf()
        Array(samples.size / 160 - 3) { frame -> FloatArray(FeatureExtractor.MEL_BINS) { samples[frame * 160] } }
    }

    /** Fake embedding: element 0 carries the newest mel value so tests can see which window was used. */
    private val fakeEmbedding: (Array<FloatArray>) -> FloatArray = { window ->
        embeddingInputs += window.map { it.copyOf() }.toTypedArray()
        FloatArray(FeatureExtractor.EMBEDDING_SIZE) { window.last()[0] }
    }

    private fun chunk(value: Short) = ShortArray(FeatureExtractor.CHUNK_SAMPLES) { value }

    @Test
    fun firstChunkHasNoLeftContext() {
        val extractor = FeatureExtractor(fakeMel, fakeEmbedding)
        extractor.accept(chunk(10))

        assertEquals(FeatureExtractor.CHUNK_SAMPLES, melInputs.single().size)
    }

    @Test
    fun laterChunksCarryTheLast480Samples() {
        val extractor = FeatureExtractor(fakeMel, fakeEmbedding)
        extractor.accept(chunk(10))
        extractor.accept(chunk(20))

        val second = melInputs[1]
        assertEquals(FeatureExtractor.CHUNK_SAMPLES + FeatureExtractor.MEL_CONTEXT_SAMPLES, second.size)
        assertArrayEquals(FloatArray(480) { 10f }, second.copyOfRange(0, 480), 0f)
        assertArrayEquals(FloatArray(1280) { 20f }, second.copyOfRange(480, second.size), 0f)
    }

    @Test
    fun melValuesAreScaledAndWindowIs76Frames() {
        val extractor = FeatureExtractor(fakeMel, fakeEmbedding)
        extractor.accept(chunk(30))

        val window = embeddingInputs.single()
        assertEquals(FeatureExtractor.MEL_WINDOW, window.size)
        // 5 new frames at the end, the rest is the initial padding of ones.
        assertEquals(1f, window[0][0], 0f)
        assertEquals(1f, window[70][0], 0f)
        assertEquals(30f / 10f + 2f, window[71][0], 1e-6f)
        assertEquals(30f / 10f + 2f, window[75][0], 1e-6f)
    }

    @Test
    fun latestReturnsNullUntilEnoughEmbeddings() {
        val extractor = FeatureExtractor(fakeMel, fakeEmbedding)
        repeat(15) { extractor.accept(chunk(it.toShort())) }
        assertNull(extractor.latest(16))

        extractor.accept(chunk(99))
        val window = requireNotNull(extractor.latest(16))
        assertEquals(16, window.size)
        assertEquals(99f / 10f + 2f, window.last()[0], 1e-6f)
        assertEquals(0f / 10f + 2f, window.first()[0], 1e-6f)
    }

    @Test
    fun resetClearsHistory() {
        val extractor = FeatureExtractor(fakeMel, fakeEmbedding)
        repeat(20) { extractor.accept(chunk(5)) }
        extractor.reset()

        assertEquals(0, extractor.size)
        extractor.accept(chunk(7))
        assertEquals(FeatureExtractor.CHUNK_SAMPLES, melInputs.last().size)
        assertEquals(1f, embeddingInputs.last()[0][0], 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongChunkSize() {
        FeatureExtractor(fakeMel, fakeEmbedding).accept(ShortArray(100))
    }
}
