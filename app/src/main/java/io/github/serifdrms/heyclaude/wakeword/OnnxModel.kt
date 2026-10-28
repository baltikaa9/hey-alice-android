package io.github.serifdrms.heyclaude.wakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.FloatBuffer

/**
 * Thin wrapper around an ONNX Runtime session with a single float input and output.
 * One thread per session: these models are tiny and run every 80 ms, so extra threads
 * would only cost battery.
 */
class OnnxModel(private val env: OrtEnvironment, modelBytes: ByteArray) : AutoCloseable {

    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setInterOpNumThreads(1)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val session: OrtSession = env.createSession(modelBytes, options)
    private val inputName: String = session.inputNames.first()

    /** Declared input shape; dynamic dimensions are reported as -1. */
    val inputShape: LongArray =
        (session.inputInfo.getValue(inputName).info as TensorInfo).shape

    fun run(input: FloatArray, shape: LongArray): FloatArray =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val buffer = (result.get(0) as OnnxTensor).floatBuffer
                FloatArray(buffer.remaining()).also { buffer.get(it) }
            }
        }

    override fun close() {
        session.close()
        options.close()
    }
}
