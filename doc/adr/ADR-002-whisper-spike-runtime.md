# ADR-002: Runtime Whisper provisional para el spike

- Estado: aceptado provisionalmente para continuar; G0 superada
- Fecha: 2026-07-28

## Contexto

El DocMaster selecciona `whisper.cpp` como primer runtime ASR y pide comparar
Whisper tiny y base multilingües cuantizados en español. G0 requiere demostrar el
pipeline local AudioRecord -> VAD -> chunks -> ASR y medir RTF, primer texto, RAM y
temperatura en el equipo real.

## Decisión provisional

- `whisper.cpp` v1.8.6 fijado al commit
  `23ee03506a91ac3d3f0071b40e66a430eebdfa1d` como submódulo.
- Build NDK/JNI CPU-only con NDK `27.1.12297006`, CMake `3.22.1` y ABI
  `arm64-v8a` para el S25; `x86_64` se conserva para laboratorio/emulador.
- Modelos oficiales multilingües `tiny-q5_1`, `base-q5_1` y `small-q5_1`, fijados por URL de
  revisión, tamaño y SHA-256 en `models/manifest.json`.
- Idioma explícito `es`, timestamps activos, greedy decoding, sin contexto entre
  chunks y supresión de tokens no hablados.
- La inferencia es serial en un hilo dedicado. El audio pasa por VAD antes de ASR.
- El ASR incremental conserva ventanas visibles de 4 s. Para el refinamiento
  offline, segmentos cercanos se agrupan hasta 30 s con huecos máximos de 3 s y
  habla continua mayor usa 500 ms de overlap. La medición física inicial mostró
  que 64 llamadas de 5–8 s a `whisper_full` repetían demasiado costo fijo; la
  agrupación offline reduce esta muestra a 16 llamadas sin cambiar el audio ni
  perder timestamps.
- Los modelos y las transcripciones permanecen en almacenamiento privado de la
  app. No se registra contenido en logs.

## Evidencia incorporada

- Compilación nativa de `libnoteapp_whisper.so` para `arm64-v8a` y `x86_64`.
- Verificación local exacta de ambos modelos descargados.
- APK de laboratorio capaz de importar modelos, transcribir una sesión finalizada
  y persistir RTF, tiempo a primer texto, PSS pico, estado térmico y temperatura
  máxima de batería.
- Licencia upstream archivada en `doc/licenses/whisper.cpp-MIT.md`.

## Alternativas pendientes

- Comparar los tres modelos mediante el corpus privado y el evaluador WER/CER de
  Sprint 2; small ya está integrado pero todavía no fue medido en el S25.
- Ajustar thread count, chunking y overlap con evidencia del S25.
- Evaluar Sherpa-ONNX si Whisper no alcanza calidad/latencia útil.
- Evaluar GPU solo después del baseline CPU reproducible. No se asume NNAPI/NPU.

## Criterio para aceptar o reemplazar

La decisión sólo podía aceptarse después de ejecutar tiny y base sobre la misma
sesión autorizada de 5–10 minutos en el S25 Ultra. Esa medición se completó y su
resultado se registra a continuación.

## Resultado físico y consecuencia

La sesión autorizada del S25 Ultra produjo 357 s de audio útil para 16 chunks:

- tiny q5_1: primer texto 51.716 ms, RTF 5,34 y PSS pico 393.695 KiB;
- base q5_1: primer texto 118.294 ms, RTF 6,91 y PSS pico 477.245 KiB.

Ambos modelos completaron sin servicios remotos ni fallo de integración, pero
incumplen ampliamente el RTF cercano a 1. G0 queda en **AJUSTAR**. El runtime
CPU-only actual puede conservarse como referencia offline, pero no se adopta como
ASR incremental del producto hasta optimizarlo o comparar un backend alternativo.

## Corrección de validez del benchmark

La auditoría del 2026-07-28 comprobó en el `build.ninja` arm64 que la APK debug
usada en G0 compiló `ggml-base` y `ggml-cpu` sin `-O2/-O3`. El `-O3` declarado
afectaba solamente al wrapper `noteapp_whisper` y sólo en configuraciones release.
Por lo tanto, los RTF 5,34/6,91 prueban integración y un límite debug, pero no
permiten rechazar todavía el rendimiento nativo de producción.

Se crea una variante `benchmark`, firmada para laboratorio y con acceso `run-as`,
que resuelve `inference-asr` contra `RelWithDebInfo`. La preparación automatizada
exige `ggml-cpu -O2 -DNDEBUG` y wrapper JNI `-O3 -DNDEBUG`. G0 permanece en
**AJUSTAR** hasta repetir tiny/base y registrar una nueva decisión.

## Resultado de revalidación

La repetición física con `RelWithDebInfo`, 4 hilos y chunks de 30 s obtuvo:

- tiny q5_1: RTF 0,153, primer texto 1.264 ms y térmica 0;
- base q5_1: RTF 0,191, primer texto 2.655 ms y térmica 0.

G0 cambia a **CONTINUAR**. Tiny queda como primer candidato incremental y base como
candidato de refinamiento final, sujetos a calidad/WER de Sprint 2 y sostenibilidad
incremental de G2. El barrido de hilos/chunks permanece como optimización posterior,
no como condición para G1.
