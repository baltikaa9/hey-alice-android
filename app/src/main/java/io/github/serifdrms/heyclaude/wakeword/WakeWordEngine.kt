package io.github.serifdrms.heyclaude.wakeword

import ai.onnxruntime.OrtEnvironment
import android.content.Context
import java.security.MessageDigest

class ModelIntegrityException(message: String) : Exception(message)

/**
 * Connects the verified bundled feature extractors and imported classifier.
 */
class WakeWordEngine private constructor(
    val detector: WakeWordDetector,
    private val models: List<OnnxModel>,
) : AutoCloseable {

    override fun close() {
        models.forEach { it.close() }
    }

    companion object {
        fun load(context: Context, threshold: Float): WakeWordEngine {
            val env = OrtEnvironment.getEnvironment()
            val loaded = mutableListOf<OnnxModel>()
            try {
                val mel = OnnxModel(env, readVerified(context, ModelCatalog.melSpectrogram)).also(loaded::add)
                val emb = OnnxModel(env, readVerified(context, ModelCatalog.embedding)).also(loaded::add)

                val model = AliceModel.load(context).also(loaded::add)
                val frames = model.inputShape[1].toInt()
                val extractor = FeatureExtractor(
                    maxFeatures = frames,
                    melSpectrogram = { samples ->
                        val out = mel.run(samples, longArrayOf(1, samples.size.toLong()))
                        Array(out.size / FeatureExtractor.MEL_BINS) { frame ->
                            out.copyOfRange(frame * FeatureExtractor.MEL_BINS, (frame + 1) * FeatureExtractor.MEL_BINS)
                        }
                    },
                    embedding = { window ->
                        val flat = FloatArray(FeatureExtractor.MEL_WINDOW * FeatureExtractor.MEL_BINS)
                        window.forEachIndexed { i, row -> row.copyInto(flat, i * FeatureExtractor.MEL_BINS) }
                        emb.run(
                            flat,
                            longArrayOf(1, FeatureExtractor.MEL_WINDOW.toLong(), FeatureExtractor.MEL_BINS.toLong(), 1),
                        )
                    },
                )

                val wakeWord = WakeWord("alice", frames) { features ->
                    val width = FeatureExtractor.EMBEDDING_SIZE
                    val flat = FloatArray(frames * width)
                    features.forEachIndexed { i, row -> row.copyInto(flat, i * width) }
                    val score = model.run(flat, longArrayOf(1, frames.toLong(), width.toLong())).single()
                    if (score in 0f..1f) score else 0f
                }

                return WakeWordEngine(WakeWordDetector(extractor, listOf(wakeWord), threshold), loaded)
            } catch (e: Exception) {
                loaded.forEach { it.close() }
                throw e
            }
        }

        private fun readVerified(context: Context, asset: ModelAsset): ByteArray {
            val bytes = context.assets.open(asset.path).use { it.readBytes() }
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            if (!actual.equals(asset.sha256, ignoreCase = true)) {
                throw ModelIntegrityException("${asset.path} hash mismatch: $actual")
            }
            return bytes
        }
    }
}
