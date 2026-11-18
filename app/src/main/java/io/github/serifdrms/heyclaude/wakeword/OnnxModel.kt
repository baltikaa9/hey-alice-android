package io.github.serifdrms.heyclaude.wakeword

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.FloatBuffer

/** Single-input, single-output float ONNX inference on one thread. */
class OnnxModel(private val env: OrtEnvironment, modelBytes: ByteArray) : AutoCloseable {

    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(1)
        options.setInterOpNumThreads(1)
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        env.createSession(modelBytes, options)
    }

    private val inputName: String
    val inputShape: LongArray

    init {
        try {
            val input = session.inputInfo.entries.single()
            val inputInfo = input.value.info as? TensorInfo
            val outputInfo = session.outputInfo.values.single().info as? TensorInfo
            require(inputInfo?.type == OnnxJavaType.FLOAT && outputInfo?.type == OnnxJavaType.FLOAT) {
                "Expected one float tensor input and output"
            }
            inputName = input.key
            inputShape = checkNotNull(inputInfo).shape
        } catch (e: Exception) {
            session.close()
            throw e
        }
    }

    fun run(input: FloatArray, shape: LongArray): FloatArray =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val buffer = (result.get(0) as OnnxTensor).floatBuffer
                FloatArray(buffer.remaining()).also { buffer.get(it) }
            }
        }

    override fun close() = session.close()
}
