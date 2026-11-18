package io.github.serifdrms.heyclaude.wakeword

/** Bundled openWakeWord feature models, pinned by SHA-256. */
data class ModelAsset(val path: String, val sha256: String)

object ModelCatalog {

    // Feature models from openWakeWord v0.5.1 (Apache-2.0).
    val melSpectrogram = ModelAsset(
        path = "models/melspectrogram.onnx",
        sha256 = "ba2b0e0f8b7b875369a2c89cb13360ff53bac436f2895cced9f479fa65eb176f",
    )
    val embedding = ModelAsset(
        path = "models/embedding_model.onnx",
        sha256 = "70d164290c1d095d1d4ee149bc5e00543250a7316b59f31d056cff7bd3075c1f",
    )
}
