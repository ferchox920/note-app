package com.noteapp.asr

import java.io.File

data class SherpaModelArtifact(
    val fileName: String,
    val expectedBytes: Long,
    val sha256: String,
)

data class SherpaStreamingModelDescriptor(
    val id: String,
    val directoryName: String,
    val language: String,
    val modelType: String,
    val license: String,
    val sourceRevision: String,
    val artifacts: List<SherpaModelArtifact>,
) {
    fun artifact(fileName: String): SherpaModelArtifact =
        requireNotNull(artifacts.firstOrNull { it.fileName == fileName }) {
            "Unknown model artifact: $fileName"
        }
}

data class SherpaModelVerificationFailure(
    val artifact: SherpaModelArtifact,
    val result: ModelVerificationResult,
)

object SherpaStreamingModelVerifier {
    fun verify(
        modelDirectory: File,
        descriptor: SherpaStreamingModelDescriptor,
    ): SherpaModelVerificationFailure? =
        descriptor.artifacts.firstNotNullOfOrNull { artifact ->
            val result = verifyArtifact(File(modelDirectory, artifact.fileName), artifact)
            if (result == ModelVerificationResult.Valid) null
            else SherpaModelVerificationFailure(artifact, result)
        }

    private fun verifyArtifact(
        file: File,
        artifact: SherpaModelArtifact,
    ): ModelVerificationResult {
        if (!file.isFile) return ModelVerificationResult.Missing
        if (file.length() != artifact.expectedBytes) {
            return ModelVerificationResult.InvalidSize(artifact.expectedBytes, file.length())
        }
        val actual = WhisperModelVerifier.sha256(file)
        return if (actual == artifact.sha256) ModelVerificationResult.Valid
        else ModelVerificationResult.InvalidChecksum(artifact.sha256, actual)
    }
}

object SherpaStreamingModelCatalog {
    val spanishKroko = SherpaStreamingModelDescriptor(
        id = "sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06",
        directoryName = "sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06",
        language = "es",
        modelType = "zipformer2",
        license = "UNRESOLVED_EXPERIMENT_ONLY",
        sourceRevision = "20cf7a4921613397841d31168796cade5b866585",
        artifacts = listOf(
            SherpaModelArtifact(
                fileName = "encoder.onnx",
                expectedBytes = 154_878_102,
                sha256 = "2d9f5ef87d1a5257f8a6687e21501c56f3aa2fcbfcfab9364dcc4ce4e06ae81b",
            ),
            SherpaModelArtifact(
                fileName = "decoder.onnx",
                expectedBytes = 617_488,
                sha256 = "d4ce176b94b25f7acc88717bc3f704fcf5d6e131aaac2e0cabab3885541181ee",
            ),
            SherpaModelArtifact(
                fileName = "joiner.onnx",
                expectedBytes = 336_817,
                sha256 = "dae35df88d676e320fcdb99217328e66dcf722bf11b0f2459e14ddb5b982ded5",
            ),
            SherpaModelArtifact(
                fileName = "tokens.txt",
                expectedBytes = 6_385,
                sha256 = "1be5e0a58e05d06d327df4c6b7b5e4f8aba01da6981eb016fcaceafc6a56680f",
            ),
        ),
    )

    val evaluationModels = listOf(spanishKroko)
}
