package io.github.serifdrms.heyclaude.wakeword

import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.InputStream

object AliceModel {
    const val PHRASE = "Алиса"
    private const val FILE_NAME = "alice.onnx"
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_FRAMES = 128

    fun isImported(context: Context): Boolean = file(context).baseFile.isFile

    @Synchronized
    fun import(context: Context, uri: Uri) {
        val bytes = context.contentResolver.openInputStream(uri)?.use(::readBounded)
            ?: throw ModelIntegrityException("Could not open the selected file")
        open(bytes).close()

        val destination = file(context)
        val output = destination.startWrite()
        try {
            output.write(bytes)
            destination.finishWrite(output)
        } catch (e: Exception) {
            destination.failWrite(output)
            throw e
        }
    }

    @Synchronized
    internal fun load(context: Context): OnnxModel = open(file(context).openRead().use(::readBounded))

    private fun file(context: Context) = AtomicFile(context.filesDir.resolve(FILE_NAME))

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            if (output.size() + count > MAX_BYTES) throw ModelIntegrityException("ONNX file exceeds 8 MiB")
            output.write(buffer, 0, count)
        }
        if (output.size() == 0) throw ModelIntegrityException("ONNX file is empty")
        return output.toByteArray()
    }

    private fun open(bytes: ByteArray): OnnxModel {
        val model = OnnxModel(OrtEnvironment.getEnvironment(), bytes)
        try {
            val input = model.inputShape
            val width = FeatureExtractor.EMBEDDING_SIZE
            if (input.size != 3 || input[0] !in listOf(-1L, 1L) ||
                input[1] !in 1L..MAX_FRAMES.toLong() || input[2] != width.toLong()
            ) {
                throw ModelIntegrityException("Expected float input [1, frames, $width]")
            }
            val frames = input[1].toInt()
            val shape = longArrayOf(1, frames.toLong(), width.toLong())
            for (value in floatArrayOf(0f, 1f)) {
                val scores = model.run(FloatArray(frames * width) { value }, shape)
                if (scores.size != 1 || scores[0] !in 0f..1f) {
                    throw ModelIntegrityException("Expected a single score between 0 and 1")
                }
            }
            return model
        } catch (e: Exception) {
            model.close()
            throw e
        }
    }
}
