package io.github.serifdrms.heyclaude.wakeword

import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.util.Log
import java.io.FileNotFoundException
import java.security.MessageDigest

class ModelIntegrityException(message: String) : Exception(message)

/**
 * Loads the bundled ONNX models, checks their hashes and wires them into a [WakeWordDetector].
 */
class WakeWordEngine private constructor(
    val detector: WakeWordDetector,
    val phrases: List<String>,
    private val models: List<OnnxModel>,
) : AutoCloseable {

    override fun close() {
        models.forEach { it.close() }
    }

    companion object {
        private const val TAG = "WakeWordEngine"

        fun load(context: Context, threshold: Float): WakeWordEngine {
            val env = OrtEnvironment.getEnvironment()
            val loaded = mutableListOf<OnnxModel>()
            try {
                val mel = OnnxModel(env, readVerified(context, ModelCatalog.melSpectrogram)).also(loaded::add)
                val emb = OnnxModel(env, readVerified(context, ModelCatalog.embedding)).also(loaded::add)

                val extractor = FeatureExtractor(
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

                val wakeWords = mutableListOf<WakeWord>()
                val phrases = mutableListOf<String>()
                for (spec in ModelCatalog.wakeWords) {
                    val bytes = try {
                        readVerified(context, spec.asset)
                    } catch (e: FileNotFoundException) {
                        Log.w(TAG, "Skipping ${spec.id}: ${spec.asset.path} is not bundled")
                        continue
                    }
                    val model = OnnxModel(env, bytes).also(loaded::add)
                    val frames = model.inputShape[1].toInt()
                    wakeWords += WakeWord(spec.id, frames) { features ->
                        val flat = FloatArray(frames * FeatureExtractor.EMBEDDING_SIZE)
                        features.forEachIndexed { i, row -> row.copyInto(flat, i * FeatureExtractor.EMBEDDING_SIZE) }
                        model.run(flat, longArrayOf(1, frames.toLong(), FeatureExtractor.EMBEDDING_SIZE.toLong()))[0]
                    }
                    phrases += spec.phrase
                }
                if (wakeWords.isEmpty()) throw FileNotFoundException("No wake word model is bundled")

                val detector = WakeWordDetector(extractor, wakeWords, threshold)
                return WakeWordEngine(detector, phrases, loaded)
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
