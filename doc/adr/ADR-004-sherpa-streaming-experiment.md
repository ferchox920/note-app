# ADR-004: Sherpa-ONNX como backend incremental experimental

- Estado: aceptado provisionalmente; no apto para distribución
- Fecha: 2026-07-29

## Contexto

Whisper tiny/base por ventanas no consiguió simultáneamente calidad y tiempo real
en el S25 Ultra. El perfil rápido alcanzó RTF 0,042 pero WER 90,45 %; conservar
contexto recuperó calidad aproximada a costa de RTF entre 2,31 y 3,94. El
documento maestro contempla Sherpa-ONNX como alternativa realmente streaming.

## Decisión

- Usar Sherpa-ONNX 1.13.4 detrás de `IncrementalAsrSession`.
- Evaluar el Zipformer español Kroko fijado por revisión, tamaño y SHA-256.
- Alimentar PCM16 mono a 16 kHz en tramas continuas de 100 ms.
- Mantener el estado del transductor durante toda la sesión y finalizar segmentos
  mediante su endpointing nativo.
- Conservar WebRTC VAD para evidencia, sin convertirlo en dependencia del
  transductor.
- Mantener Whisper base q5_1 como refinamiento final provisional.
- No distribuir el modelo mientras su licencia exacta y versión no estén
  resueltas.

## Evidencia

El replay físico congelado obtuvo WER 20,22 %, primer texto tras 1,5 s de audio y
RTF 0,0257. La lectura física en vivo obtuvo RTF 0,0900, latencia de cola p95 de
1 ms, captura perfecta y WER 24,72 %, sin descartes ni errores técnicos.

La evidencia completa y sus límites están en
[`../evidence/sprint-3/incremental-optimization-2026-07-28.md`](../evidence/sprint-3/incremental-optimization-2026-07-28.md).

## Consecuencias

El backend cumple rendimiento de tiempo real y mejora drásticamente la calidad
visible frente al Whisper rápido. A cambio, aumenta el APK, introduce ONNX Runtime
y obliga a alinear su ABI con Silero. La calidad en vivo aún supera el máximo
aceptable de 22 %, el refinamiento final necesita revalidación en frío y G2 exige
una sesión continua de 45 minutos y repetición en S25+.

## Fuentes

- [Sherpa-ONNX para Android](https://k2-fsa.github.io/sherpa/onnx/android/index.html)
- [Release 1.13.4 de Sherpa-ONNX](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.4)
- [Repositorio fijado del modelo experimental](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06/tree/20cf7a4921613397841d31168796cade5b866585)
