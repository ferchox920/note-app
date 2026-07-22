# Evidencia del spike técnico

G0 se cerró en **AJUSTAR** sobre un Galaxy S25 Ultra físico. La sesión autorizada
validó captura/VAD sin pérdidas, pero tiny/base CPU-only midieron RTF 5,34/6,91.
La ficha sanitizada enlazada al final contiene la decisión y las métricas.

## Configuración base

- Build: `0.1.0-spike` (`versionCode 1`)
- JDK: 17
- Compile/target SDK: 36
- AGP: 8.13.2
- Gradle: 8.13
- Compose BOM: 2026.06.01

## Evidencia requerida para cerrar G0

- Audio autorizado de 5 a 10 minutos: lectura, conversación y ruido.
- AudioRecord mono PCM16 sin pérdida ni desorden.
- Segmentos VAD con timestamps.
- Comparativa Whisper tiny/base cuantizados.
- Tiempo a primer texto, RTF, RAM pico y temperatura.
- Checksums de modelos, build exacta y configuración de captura.

No incluir audio, transcripciones ni notas sensibles en logs o fixtures.

## Verificación de la base (2026-07-21)

- Comando: `.\gradlew.bat build lint test --stacktrace`
- Resultado: `BUILD SUCCESSFUL`
- Pruebas de dominio: 3 debug + 3 release, 0 fallos y 0 errores.
- Artefacto debug generado: `app/build/outputs/apk/debug/app-debug.apk`.
- G0: pendiente; esa verificación correspondía a la base previa a AudioRecord.

## Incremento de captura (2026-07-21)

- AudioRecord PCM16 mono a 16 kHz integrado en foreground service `microphone`.
- Pausa/reanudación crea segmentos independientes.
- Cada segmento registra bytes y SHA-256; el checkpoint se reemplaza atómicamente.
- Verificación automatizada: 10 ejecuciones de pruebas (debug/release), 0 fallos;
  `build`, `lint` y `test` exitosos.
- La prueba real debe seguir
  [`audio-capture-protocol.md`](audio-capture-protocol.md).
- No había ningún dispositivo conectado por ADB durante esta verificación, por lo
  que todavía no se afirma calidad ni estabilidad de captura física.
- En ese incremento G0 quedaba pendiente: faltaba ejecutar AudioRecord/VAD en el
  S25 Ultra e integrar whisper.cpp.

## Incremento WebRTC VAD (2026-07-21)

- Módulo `inference-vad` añadido con JNI WebRTC VAD offline.
- Frames exactos de 20 ms aunque AudioRecord entregue buffers desalineados.
- Endpointing provisional: 60 ms de voz, 200 ms de pre-roll y 300 ms de hangover.
- `vad-segments.json` registra inicio/fin en ms y offsets PCM en bytes.
- El APK incluye `libvad_jni.so` para arm64-v8a, armeabi-v7a, x86 y x86_64.
- La captura PCM continúa y muestra un error técnico si VAD no puede inicializarse.
- La ejecución JNI y la calidad de segmentación siguen pendientes de dispositivo.
- Verificación completa: `BUILD SUCCESSFUL`, 20 ejecuciones de pruebas
  debug/release, 0 fallos y 0 errores.
- Lint de `core-audio` e `inference-vad`: 0 observaciones. Las seis advertencias
  globales restantes corresponden a upgrades de toolchain retenidos por ADR-000.

## Incremento whisper.cpp ASR (2026-07-21)

- `whisper.cpp` v1.8.6 fijado como submódulo al commit `23ee03506a91ac3d3f0071b40e66a430eebdfa1d`.
- JNI CPU-only compilado para `arm64-v8a` y `x86_64` con NDK 27.1/CMake 3.22.1.
- Modelos multilingües tiny/base `q5_1` descargados y verificados por tamaño y
  SHA-256 contra `models/manifest.json`; los binarios están ignorados por Git.
- La APK importa modelos mediante Storage Access Framework y los vuelve a verificar.
- El runner usa el timeline VAD, agrupa/splitea chunks de hasta 8 s y persiste
  timestamps, RTF, primer texto, PSS pico, estado térmico y temperatura de batería.
- Verificación de compilación nativa, tests ASR y APK: `BUILD SUCCESSFUL`.
- La ejecución y comparación real tiny/base sigue el protocolo
  [`asr-lab-protocol.md`](asr-lab-protocol.md).
- En ese incremento G0 quedaba pendiente porque no había un S25 Ultra conectado.

## Incremento de recuperación de Sprint 1 (2026-07-21)

- `core-storage` descubre sesiones no terminales al iniciar la app.
- La UI permite reanudar una sesión interrumpida desde almacenamiento privado.
- La recuperación rechaza cambios de tamaño/checksum, incorpora el último segmento
  huérfano válido y nunca reutiliza su nombre de archivo.
- El timeline VAD se restaura y el tail PCM no persistido se reprocesa antes de
  continuar AudioRecord.
- Tests focalizados de writer, descubrimiento y continuidad VAD exitosos; lint de
  `core-audio`, `core-storage`, `inference-vad` y `feature-recording`: 0 hallazgos.
- Falta ejecutar el cierre forzado real siguiendo
  [`../sprint-1/recovery-protocol.md`](../sprint-1/recovery-protocol.md).

## Cierre arquitectónico de Sprint 1 (2026-07-21)

- Navigation Compose incorpora el grafo single-activity con destino de grabación.
- Hilt/KSP genera y valida el grafo de dependencias de app y ViewModel.
- Checkpoint durable cada 10 s mientras graba; conserva errores de lectura,
  discontinuidades detectadas con `AudioTimestamp` y frames faltantes estimados.
- Verificación completa posterior: 42 tests debug/release, 0 fallos; build y lint
  exitosos. Todos los módulos tienen 0 hallazgos propios; los avisos globales son
  upgrades deliberadamente retenidos por compatibilidad y están en ADR-000.

## Inicio del arnés de evaluación Sprint 2 (2026-07-21)

- Whisper small multilingüe `q5_1` incorporado al catálogo y APK.
- Binario small verificado: 190.085.487 bytes, SHA-256
  `ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb`.
- Esquema de corpus privado preparado para lectura, conversación, ruido y silencio.
- Evaluador reproducible genera JSON/CSV con WER, CER, sustituciones, omisiones,
  inserciones, RTF y alucinaciones de silencio; 3 tests Python pasan.
- Falta corpus autorizado y ejecución física según
  [`../sprint-2/asr-evaluation-protocol.md`](../sprint-2/asr-evaluation-protocol.md).

## Comparación de frecuencia de captura (2026-07-21)

- La APK permite elegir 16 kHz directo o 48 kHz nativo con salida normalizada a
  16 kHz mediante FIR/decimación 3:1.
- Tests verifican conteo exacto de muestras, invariancia entre buffers y supresión
  de frecuencias por encima de Nyquist.
- Pipeline y frecuencia de captura quedan en checkpoint, UI, recuperación y JSON ASR.
- El evaluador agrupa resultados por modelo y pipeline para evitar mezclar métricas.
- La decisión permanece pendiente del protocolo
  [`../sprint-2/capture-rate-comparison.md`](../sprint-2/capture-rate-comparison.md).

## Comparación WebRTC/Silero (2026-07-21)

- Silero 2.0.10-cf.4 integrado con ONNX Runtime Android 1.22.0.
- Runner offline procesa el mismo PCM con WebRTC 20 ms y Silero 32 ms, aplicando
  el mismo endpointing externo.
- La APK muestra segmentos, cobertura y RTF, y persiste timelines separados.
- Build y lint pasan; las bibliotecas ONNX se empaquetan para arm64-v8a y el APK
  debug de laboratorio crece a aproximadamente 138 MB.
- Falta evaluación con anotaciones humanas según
  [`../sprint-2/vad-comparison-protocol.md`](../sprint-2/vad-comparison-protocol.md).
- El esquema del corpus admite intervalos humanos por item y `evaluate_vad.py`
  produce JSON/CSV con precisión, recall, F1, habla perdida, falsos positivos,
  errores de borde y fragmentación agrupados por motor y pipeline de captura.
- Seis pruebas Python cubren ahora los evaluadores ASR y VAD; la CI ejecuta ambas.

## Base determinista de Sprint 3 (2026-07-21)

- Ring buffer acotado a 4 s y parciales cada 3 s, con overlap efectivo de 1 s.
- Cola de inferencia acotada a dos ventanas y contador de descartes oldest-first.
- Reconciliador conserva el prefijo estable y deja al menos una palabra provisional.
- Este primer corte se verificó antes de conectarlo a captura; la integración se
  registra en el incremento siguiente. G0/G1 siguen pendientes y G2 debe medirse según
  [`../sprint-3/incremental-transcription-protocol.md`](../sprint-3/incremental-transcription-protocol.md).

## Integración incremental en foreground service (2026-07-21)

- El coordinador se conectó al PCM normalizado y al endpoint WebRTC VAD dentro del
  ciclo de vida del servicio, independiente de la pantalla.
- tiny/base/small instalados pueden seleccionarse explícitamente por sesión; un
  error ASR no detiene la captura.
- Cola final-aware acotada, reconciliación de ventanas deslizantes y métricas visibles
  de latencia/RTF/descartes/conflictos.
- Segmentos finalizados se guardan atómicamente con timestamps y sobreviven a la
  recuperación; la hipótesis provisional interrumpida se descarta.
- APK debug compila y los tests focalizados debug/release pasan; G2 continúa pendiente
  de la sesión física de 45 minutos.
- Cada ventana registra una serie temporal y `evaluate_incremental.py` calcula
  latencia/primer texto p50-p95, RTF ponderado, descartes y conflictos por
  modelo/pipeline. Nueve tests Python cubren ahora ASR, VAD y evaluación incremental.
- Verificación integral posterior: 72 tests Android y 9 Python, 0 fallos. Lint tiene
  0 hallazgos por módulo; `app` mantiene 10 avisos de upgrades retenidos por ADR-000.
- APK debug: 138.534.559 bytes. ADB no reportó dispositivos, por lo que no existe
  evidencia válida para cerrar G0/G1/G2.

## Automatización de evidencia física (2026-07-22)

- `collect-device-session.ps1` exige un dispositivo ADB autorizado, extrae con
  `run-as`, valida el TAR contra path traversal y guarda todo bajo `artifacts/private/`.
- `verify_session_artifacts.py` comprueba PCM/checksums/offsets, checkpoint, VAD,
  ASR offline e incremental sin incluir texto o audio en su reporte.
- El modo de recuperación admite el segmento huérfano contiguo y comprueba que el
  total checkpointado quede entre los bytes listados y los recuperables.
- Los tests del validador pasan; la APK debug recompila con el ID de sesión seleccionable.
- Guía consolidada: [`../device-gates/README.md`](../device-gates/README.md).
- `evaluate_device_gates.py` aplica los umbrales exactos G0/G1/G2 sobre evidencia
  sanitizada, exige S25 Ultra y nunca marca una puerta como aprobada automáticamente.
- Diecisiete tests Python cubren ahora evaluadores, verificación y decisiones de puerta.

## Preparación física de G0 (2026-07-22)

- ADB verificó el dispositivo autorizado `R5CY20HYBGJ`, modelo `SM-S938B`, con
  Android 16/API 36.
- La APK debug se compiló, instaló y abrió correctamente; los permisos de micrófono
  y notificaciones quedaron concedidos.
- Los modelos `ggml-tiny-q5_1.bin` y `ggml-base-q5_1.bin` se transfirieron, se
  validaron por SHA-256 y se importaron al almacenamiento privado de la app.
- La sesión final duró 6:18: tres PCM, 12.108.800 bytes, 127 segmentos VAD, cero
  errores de lectura, cero discontinuidades y checksums válidos.
- Tiny produjo RTF 5,34 y base RTF 6,91 sobre los mismos 357 s de audio VAD.
- Decisión G0: **AJUSTAR** el runtime/configuración ASR antes de G1/G2.
- Ficha sanitizada de la ejecución: [`g0-device-run-2026-07-22.md`](g0-device-run-2026-07-22.md).
