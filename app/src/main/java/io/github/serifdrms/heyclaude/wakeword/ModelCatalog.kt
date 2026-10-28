package io.github.serifdrms.heyclaude.wakeword

/**
 * Every model the app ships, pinned by SHA-256. A model whose bytes don't match is never
 * loaded, so a swapped or corrupted file in the repo can't silently change what the app
 * listens for.
 *
 * After training a new model, get its hash with
 *   PowerShell: Get-FileHash .\hey_claude.onnx -Algorithm SHA256
 *   Linux/macOS: sha256sum hey_claude.onnx
 * and add it below.
 */
data class ModelAsset(val path: String, val sha256: String)

data class WakeWordSpec(
    val id: String,
    val phrase: String,
    val asset: ModelAsset,
)

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

    val wakeWords = listOf(
        // Trained with the openWakeWord automatic training pipeline on synthetic speech.
        // Licensed CC BY-NC-SA 4.0 because its negative training data is; see NOTICE.
        WakeWordSpec(
            id = "hey_claude",
            phrase = "Hey Claude",
            asset = ModelAsset(
                path = "models/hey_claude.onnx",
                sha256 = "3b37e6def0e31c302520da8434c5720c7da6409874dfe721edf0acd0b0125f7f",
            ),
        ),
    )
}
