package com.noteapp.asr

data class WhisperModelDescriptor(
    val id: String,
    val fileName: String,
    val quantization: String,
    val expectedBytes: Long,
    val sha256: String,
)

object WhisperModelCatalog {
    val tinyQ5_1 = WhisperModelDescriptor(
        id = "whisper-tiny-multilingual-q5_1",
        fileName = "ggml-tiny-q5_1.bin",
        quantization = "q5_1",
        expectedBytes = 32_152_673,
        sha256 = "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7",
    )

    val baseQ5_1 = WhisperModelDescriptor(
        id = "whisper-base-multilingual-q5_1",
        fileName = "ggml-base-q5_1.bin",
        quantization = "q5_1",
        expectedBytes = 59_707_625,
        sha256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898",
    )

    val smallQ5_1 = WhisperModelDescriptor(
        id = "whisper-small-multilingual-q5_1",
        fileName = "ggml-small-q5_1.bin",
        quantization = "q5_1",
        expectedBytes = 190_085_487,
        sha256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
    )

    val spikeModels = listOf(tinyQ5_1, baseQ5_1)
    val evaluationModels = listOf(tinyQ5_1, baseQ5_1, smallQ5_1)
}
