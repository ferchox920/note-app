# Plan de sprints - MVP Android local de transcripción y notas estructuradas

## 1. Objetivo del MVP

Construir una app Android nativa, offline-first, optimizada inicialmente para Galaxy S25 Ultra y S25+, capaz de:

- grabar conversaciones presenciales de 45 a 90 minutos con la pantalla apagada;
- pausar, reanudar y recuperar sesiones interrumpidas;
- detectar voz y silencio;
- transcribir localmente español en bloques cortos, con texto incremental y timestamps;
- guardar audio, transcripción, notas y metadatos de forma local y cifrada;
- generar al finalizar una nota estructurada, editable y trazable a la transcripción;
- buscar sesiones y exportarlas a Markdown o TXT;
- demostrar con mediciones reales su calidad, latencia, memoria, batería y comportamiento térmico.

## 2. Supuestos de planificación

- Equipo: una persona desarrolladora Android.
- Cadencia: un spike técnico inicial de 3 días y 8 sprints de 2 semanas.
- Duración estimada: 16 semanas más el spike inicial.
- Dispositivos: S25 Ultra como equipo principal; S25+ para validación cruzada.
- Stack base: Kotlin, Jetpack Compose, coroutines/Flow, Hilt, Room, DataStore, SQLCipher, BiometricPrompt, AudioRecord y Foreground Service.
- ASR inicial: whisper.cpp mediante NDK/JNI, empezando por Whisper tiny y base multilingüe cuantizados.
- VAD inicial: WebRTC VAD; Silero queda como alternativa medida.
- Notas: Gemma 3n E2B-it con LiteRT-LM, ejecutada solamente al finalizar la sesión.
- No se compromete una fecha de release hasta superar la puerta técnica del spike.

## 3. Alcance comprometido

### Incluido

- Creación, grabación, pausa, reanudación y recuperación de sesiones.
- Captura PCM mono, VAD, endpointing y ensamblado de chunks.
- Transcripción incremental local en español con timestamps.
- Refinamiento final de la transcripción.
- Historial, búsqueda y edición manual.
- Persistencia local cifrada y acceso biométrico opcional.
- Nota general y una plantilla profesional concreta.
- Evidencia/timestamp para los ítems importantes de la nota.
- Exportación Markdown y TXT.
- Instrumentación y benchmarks en S25 Ultra y S25+.

### Fuera del MVP

- Diarización fiable o identificación automática de hablantes.
- Resumen o nota estructurada durante la grabación.
- Sincronización con escritorio o nube.
- Dependencia de APIs privadas de Samsung o Galaxy AI.
- Garantía de uso de NPU.
- Traducción, captura multipista, glosarios personalizados y denoising avanzado.
- Soporte optimizado para una matriz amplia de dispositivos Android.

## 4. Puertas de decisión

| Puerta | Momento | Condición para continuar | Si no se cumple |
|---|---|---|---|
| G0 - Viabilidad técnica | Fin del spike | Audio + VAD + Whisper tiny/base funcionan en el S25 Ultra; RTF cercano o menor a 1, sin fallos de integración | Reducir modelo/chunk, probar otro build/backend o detener para reestimar |
| G1 - Captura fiable | Fin del Sprint 1 | Grabación de 90 min con pantalla apagada, sin pérdida, con pausa/reanudación y checkpoint recuperable | No avanzar a UX incremental; corregir ciclo de vida y almacenamiento |
| G2 - ASR útil | Fin del Sprint 3 | Texto incremental sin duplicaciones graves, latencia visible menor o igual a 6 s y RTF sostenido menor o igual a 1 | Ajustar modelo, ventanas y refinamiento; considerar Sherpa-ONNX |
| G3 - Datos seguros | Fin del Sprint 4 | Cierre forzado no corrompe la sesión; base y archivos protegidos; reanudación validada | Bloquear features de notas y release |
| G4 - Nota fiel | Fin del Sprint 6 | Nota editable, estructurada y trazable; sin distorsiones sensibles en el corpus de evaluación | Simplificar plantilla o usar pipeline más extractivo |
| G5 - Release candidate | Fin del Sprint 8 | Pruebas 45/60/90 min aprobadas en ambos equipos y ningún criterio bloqueante activo | No liberar; abrir sprint de estabilización |

## 5. Definición de terminado global

Una historia solo se considera terminada cuando:

- tiene criterios de aceptación automatizados o reproducibles;
- no incluye audio ni texto sensible en logs;
- registra errores recuperables y estados de procesamiento;
- incluye pruebas unitarias o de integración proporcionales al riesgo;
- fue probada en un dispositivo real cuando toca audio, lifecycle, JNI o rendimiento;
- documenta modelo, cuantización, runtime, delegate y build usados;
- deja evidencia reproducible: resultado, captura, trace, benchmark o ADR.

Cada sprint debe archivar como mínimo: objetivo, build exacta, configuración, corpus, resultados p50/p95, riesgos observados y decisión tomada.

## 6. Plan por sprint

### Spike técnico - Viabilidad del pipeline (3 días)

**Meta:** demostrar que el núcleo AudioRecord -> VAD -> chunks -> whisper.cpp puede ejecutarse localmente en un S25 Ultra antes de comprometer el roadmap.

**Estado al 2026-07-22: G0 = AJUSTAR.** La sesión física autorizada validó captura
directa a 16 kHz y WebRTC VAD sin pérdidas, pero Whisper tiny/base CPU-only midió
RTF 5,34/6,91. Antes de comprometer G1/G2 debe optimizarse o sustituirse el backend
ASR y repetirse la medición. Evidencia sanitizada:
[`doc/evidence/spike/g0-device-run-2026-07-22.md`](doc/evidence/spike/g0-device-run-2026-07-22.md).

**Revalidación G0.1 iniciada el 2026-07-28.** La auditoría comprobó que aquella
sesión usó una APK debug donde `ggml-cpu` no tenía `-O2/-O3`. Ya existe una variante
`benchmark` instalable que resuelve las bibliotecas nativas contra release, verifica
`-O2/-O3` y `-DNDEBUG`, y permite barrer 2/4/6/8 hilos y chunks de 10/20/30 s.
Protocolo:
[`doc/evidence/spike/g0-optimized-benchmark-protocol.md`](doc/evidence/spike/g0-optimized-benchmark-protocol.md).

**Resultado G0.1: CONTINUAR.** Con `RelWithDebInfo`, 4 hilos y chunks de 30 s,
tiny obtuvo RTF 0,153 y base 0,191, con primer texto de 1,26/2,66 s y térmica 0.
G0 queda superada técnicamente; el siguiente gate es G1, sin confundir esta prueba
offline con la validación incremental G2. Evidencia:
[`doc/evidence/spike/g0-device-run-optimized-2026-07-28.md`](doc/evidence/spike/g0-device-run-optimized-2026-07-28.md).

**Backlog prioritario**

- P0. Crear proyecto Android mínimo en Kotlin/Compose con build reproducible.
- P0. Integrar AudioRecord mono PCM16 y capturar una muestra corta.
- P0. Integrar WebRTC VAD y producir segmentos con timestamps.
- P0. Integrar whisper.cpp por JNI.
- P0. Ejecutar Whisper tiny y base cuantizados sobre muestras en español.
- P0. Medir tiempo a primer texto, RTF, RAM pico y temperatura.
- P1. Comparar captura directa a 16 kHz contra 48 kHz con resample a 16 kHz.
- P1. Crear ADR-001 con elección provisional de captura, modelo y tamaño de chunk.

**Criterios de aceptación**

- Una grabación de 5 a 10 minutos produce segmentos y transcripción completamente offline.
- No se pierde ni desordena audio durante la prueba.
- Existen resultados comparables para tiny y base.
- La integración no depende de NNAPI ni de servicios remotos.
- G0 tiene una decisión explícita: continuar, ajustar o reestimar.

**Entregables:** APK de laboratorio, benchmark inicial y ADR-001.

---

### Sprint 1 - Base modular y captura fiable

**Meta:** establecer la arquitectura mínima y grabar 90 minutos de forma estable, incluso con pantalla apagada.

**Estado al 2026-07-28: EN VALIDACIÓN FÍSICA (resultado parcial: AJUSTAR).** La implementación ya contiene
foreground service de micrófono, estados de sesión, pausa/reanudación, checkpoint
atómico cada 10 s, segmentos PCM con SHA-256, recuperación de segmento huérfano y
métricas de lectura/discontinuidad. Cada transición queda además en un diario
técnico inmutable con secuencia, duración y origen del comando, sin contenido
sensible. G1 se valida con tres casos separados: 90 min
con pantalla apagada, cierre forzado recuperable e interrupción por llamada.
La ejecución larga actual terminó íntegra a los 72,59 min, por decisión manual,
por lo que no satisface todavía 90 min/80 min de pantalla apagada. El defecto de
notificación encontrado en background fue corregido y su regresión física pasó;
el caso B de cierre forzado y recuperación también pasó sin modificar segmentos
previos. El caso C y la repetición estricta de A siguen pendientes. Avance:
[`doc/evidence/sprint-1/g1-device-progress-2026-07-28.md`](doc/evidence/sprint-1/g1-device-progress-2026-07-28.md).
Protocolo:
[`doc/evidence/sprint-1/g1-device-gate-protocol.md`](doc/evidence/sprint-1/g1-device-gate-protocol.md).
Auditoría de preparación:
[`doc/evidence/sprint-1/g1-readiness-audit-2026-07-28.md`](doc/evidence/sprint-1/g1-readiness-audit-2026-07-28.md).

**Backlog prioritario**

- P0. Configurar módulos iniciales: `app`, `core-domain`, `core-audio`, `core-storage`, `feature-recording`, `benchmark` y `shared-testing`.
- P0. Implementar navegación, Hilt, estado UDF/ViewModel y manejo de permisos.
- P0. Implementar Foreground Service visible para grabación.
- P0. Implementar estados NEW, RECORDING, PAUSED, RECOVERING, COMPLETED, FAILED y ABORTED.
- P0. Implementar pausa/reanudación y escritura por segmentos con checksum.
- P0. Guardar checkpoint mínimo que permita detectar una sesión incompleta.
- P0. Probar pantalla apagada, app en background e interrupción por llamada.
- P1. Añadir medición de duración, bytes, errores de lectura y discontinuidades.
- P1. Preparar CI para build, lint y pruebas unitarias.

**Criterios de aceptación**

- Graba 90 minutos con pantalla apagada sin huecos ni corrupción.
- Pausar y reanudar no pierde más audio que la transición esperada y documentada.
- Una sesión interrumpida aparece como recuperable al reabrir la app.
- La notificación persistente refleja el estado actual.
- El build de CI es reproducible.

**Evidencia:** audio de prueba, log técnico sanitizado, prueba de cierre forzado y ADR de formato de audio.

---

### Sprint 2 - Segmentación, VAD y ASR offline

**Meta:** obtener segmentos fiables y elegir una configuración ASR inicial usando un corpus propio versionado.

**Backlog prioritario**

- P0. Implementar frames de VAD de 20 a 30 ms.
- P0. Implementar hangover, endpointing conservador y ensamblador de chunks.
- P0. Persistir offsets de inicio/fin y relación con el audio maestro.
- P0. Preparar corpus inicial con lectura, conversación y ruido, con ground truth.
- P0. Medir WER/CER, RTF, omisiones, inserciones y alucinaciones en silencio.
- P0. Comparar Whisper tiny, base y small cuantizados sobre el mismo corpus.
- P1. Comparar WebRTC VAD con Silero VAD en un subconjunto fijo.
- P1. Evaluar 16 kHz directo frente a 48 kHz + resample.

**Criterios de aceptación**

- Cada segmento tiene timestamps reproducibles y no corta frases de forma sistemática.
- Existe un reporte comparable de tiny/base/small.
- Se selecciona un modelo principal y uno de fallback mediante ADR.
- WER espontáneo es menor o igual a 25% en el ambiente objetivo inicial, o existe un plan de mitigación aprobado antes de seguir.
- El ASR offline no alucina texto de forma recurrente durante silencios.

**Evidencia:** corpus versionado, JSON/CSV de segmentos, reporte WER/CER/RTF y ADR de modelo/VAD.

---

### Sprint 3 - Transcripción incremental estable

**Meta:** mostrar texto útil durante la grabación sin tartamudeos, repeticiones ni reescrituras excesivas.

**Optimización iniciada el 2026-07-28.** El evaluador ahora calcula RTF sobre
cobertura temporal única, sin contar dos veces ventanas solapadas. El coordinador
reutiliza una hipótesis parcial únicamente cuando la ventana final es exactamente
la misma y el pre-roll usa un ring buffer primitivo sin boxing por muestra. Las
pruebas deterministas pasan. El baseline físico tiny de 125,66 s mantuvo la
captura perfecta, pero falló G2 con RTF 1,896, latencia p95 8,162 s y overflow
de finales. La segunda iteración conserva el VAD de evidencia y coalesce para ASR
las pausas menores a 1 s; falta repetir el benchmark físico antes de elegir
tiny/base o evaluar G2. Evidencia:
[`doc/evidence/sprint-3/incremental-optimization-2026-07-28.md`](doc/evidence/sprint-3/incremental-optimization-2026-07-28.md).

La medición coalescida redujo finales de 23 a 4, eliminó el overflow y mejoró el
RTF de 1,896 a 1,468, pero la latencia parcial p95 subió a 11,447 s. La siguiente
iteración usa decodificación incremental sin timestamps internos ni fallbacks de
temperatura, con límite proporcional de tokens. Su medición física alcanzó primer
texto en 3,573 s, latencia p95 de 149 ms y RTF 0,044, pero fue rechazada manualmente
por loops sobre audio ambiente. Se añadió supresión conservadora de repetición y
una lectura controlada con WER.

La lectura controlada `282e9873-1c38-4ca7-b9a1-846ee89f37e2` confirmó captura
perfecta y rendimiento incremental (primer texto 3,163 s, p95 154 ms, RTF 0,042),
pero rechazó el perfil rápido por WER 90,45 % y 25 hipótesis repetitivas
suprimidas. Sobre el mismo audio, el refinamiento offline base q5_1 en bloques de
30 s obtuvo WER 21,91 % y RTF 0,100. Tiny en bloques de 10 s mostró el intercambio
no viable del backend pseudo-streaming: WER 82,02 % con contexto recortado, o WER
28,65–30,34 % con RTF 2,31–3,94 al conservar contexto. G2 continúa pendiente; el
siguiente experimento será un backend realmente streaming, con Sherpa-ONNX como
ruta de mitigación; en ese momento se mantuvo Whisper base como candidato a
refinamiento final. Evidencia:
[`doc/evidence/sprint-3/incremental-optimization-2026-07-28.md`](doc/evidence/sprint-3/incremental-optimization-2026-07-28.md).

**Experimento Sherpa-ONNX del 2026-07-29.** Se integró un transductor Zipformer
streaming en español detrás de la misma interfaz del foreground service. Sobre el
PCM congelado de la lectura anterior obtuvo WER 20,22 %, primer texto tras 1,5 s
de audio, RTF 0,0257 y estado térmico 0. Una segunda lectura en vivo mantuvo
captura perfecta, cero descartes, latencia de cola p95 de 1 ms y RTF 0,0900; su
WER bruto de sesión completa fue 24,72 %. Esa medición incluía 23 palabras
capturadas antes de comenzar el texto controlado. La alineación semiglobal del
tramo leído —que solo excluye palabras externas anteriores/posteriores y penaliza
todos los errores internos— produjo WER 11,80 % sobre 178 palabras, dentro del
máximo aceptable de 22 %. La medición inicial de 11,86 s desde pulsar «Iniciar»
incluía 10,4 s previos a la primera frase; la latencia algorítmica derivada fue
1,46 s.
El adaptador ahora agrupa las lecturas de captura en tramas de 100 ms para evitar
persistir y recomponer una métrica cada ~20 ms. Sherpa queda seleccionado
provisionalmente tanto para el texto en vivo como para el cierre de cada segmento.
El verificador G2 usa desde su esquema 2 cobertura temporal única para el RTF,
por lo que las ventanas solapadas no abaratan artificialmente la inferencia; la
matriz rechaza evidencia G2 generada con el cálculo anterior.
La preparación de la prueba larga eliminó además dos costes cuadráticos: cada
métrica se agrega ahora a una lista persistente con compartición estructural y
los checkpoints agregan únicamente métricas nuevas a un journal. El JSON
autocontenido se materializa una sola vez al finalizar. Un soak JVM equivalente
a 45 minutos validó 27.000 métricas y 270 checkpoints, incluida recuperación de
una cola no confirmada.
La UX incremental ya separa el segmento final del texto provisional sin repetir
el prefijo confirmado. Los segmentos finalizados se recorren con anterior,
siguiente o selector de timestamp, usando `hh:mm:ss` en sesiones de una hora o
más. Esta navegación es textual; el salto al audio requerirá el reproductor PCM
de sesiones y no se declara implementado todavía.
Whisper base no se activa automáticamente como refinamiento: una repetición en
frío sobre esta entrada superó RTF 1 sin completar y los intentos diagnósticos
acotados fueron revertidos. G2 sigue pendiente hasta una prueba continua de
45 minutos, la validación en S25+ y la resolución de la licencia exacta del
modelo. Evidencia:
[`doc/evidence/sprint-3/incremental-optimization-2026-07-28.md`](doc/evidence/sprint-3/incremental-optimization-2026-07-28.md)
y [`doc/adr/ADR-004-sherpa-streaming-experiment.md`](doc/adr/ADR-004-sherpa-streaming-experiment.md).

**Backlog prioritario**

- P0. Implementar ring buffer PCM y ventanas de trabajo.
- P0. Emitir hipótesis parciales cada 2 a 4 segundos.
- P0. Implementar overlap de 0,5 a 1 segundo.
- P0. Reconciliar hipótesis y confirmar solo el prefijo estable.
- P0. Cerrar segmentos ante silencio o límite de duración.
- P0. Separar visualmente texto provisional y texto final. Implementado y
  confirmado en el S25 Ultra con una sesión persistida.
- P0. Añadir timestamps navegables por segmento. Implementada la navegación
  textual; queda pendiente enlazarla al futuro reproductor PCM.
- P1. Ejecutar refinamiento final al cerrar cada segmento sin bloquear la captura.
- P1. Aplicar backpressure y límites de cola para evitar acumulación indefinida.

**Criterios de aceptación**

- Tiempo a primer texto parcial menor o igual a 4 s en condiciones objetivo.
- Latencia incremental visible menor o igual a 6 s.
- RTF sostenido menor o igual a 1 en la configuración seleccionada.
- No hay duplicaciones graves al cruzar ventanas.
- La captura sigue estable mientras ASR está bajo carga.
- Una prueba continua de 45 minutos supera G2.

**Evidencia:** video del flujo, benchmark p50/p95, trace y pruebas golden de reconciliación.

---

### Sprint 4 - Persistencia, cifrado y recuperación

**Meta:** garantizar que las sesiones sean privadas, consistentes y recuperables.

**Backlog prioritario**

- P0. Implementar Room para sesiones, segmentos, notas, trabajos y métricas.
  **Completado (2026-07-29):** esquema v1 exportado, relaciones y DAOs para las
  cinco entidades; índice idempotente de sesiones, segmentos y resúmenes
  incrementales; ciclo transaccional de trabajos ASR y métricas operativas;
  recuperación de trabajos interrumpidos y cascada completa, validados en S25
  Ultra. La generación de contenido de notas consumirá este almacenamiento en
  Sprint 6. Véase
  [`doc/evidence/sprint-4/room-foundation-2026-07-29.md`](doc/evidence/sprint-4/room-foundation-2026-07-29.md).
- P0. Implementar DataStore para preferencias y flags no relacionales.
  **Completado (2026-07-29):** Proto DataStore tipado y singleton para pipeline
  de captura, modelo ASR incremental y configuración de benchmark. Incluye
  valores conservadores, validación de dominio, recuperación ante corrupción y
  consumo real desde la UI/`RecordingViewModel`; persistencia tras cierre
  forzado validada en S25 Ultra. Véase
  [`doc/evidence/sprint-4/proto-datastore-2026-07-29.md`](doc/evidence/sprint-4/proto-datastore-2026-07-29.md).
- P0. Implementar SQLCipher y gestión de claves con Android Keystore.
  **Completado (2026-07-29):** Room opera sobre SQLCipher 4.17.0 con una clave
  aleatoria de 256 bits protegida por AES-256-GCM en Android Keystore. Incluye
  migración atómica de bases SQLite existentes, recuperación de intercambios
  interrumpidos, verificación de integridad y fallo cerrado ante clave ausente
  o alterada. La migración de los datos reales y el segundo arranque fueron
  validados en S25 Ultra sin grabar ni retranscribir. Véase
  [`doc/evidence/sprint-4/sqlcipher-keystore-2026-07-29.md`](doc/evidence/sprint-4/sqlcipher-keystore-2026-07-29.md).
- P0. Cifrar archivos de audio y artefactos sensibles en reposo.
  **Completado (2026-07-31):** AES-256-GCM por frames con clave no exportable de
  Android Keystore, IV generado por el proveedor seguro, migración idempotente,
  binding de ruta, escrituras atómicas y recuperación segura de un tail
  append-only incompleto. En S25 Ultra se autenticaron dos veces 14 sesiones,
  114 artefactos y 24 segmentos PCM; no quedaron archivos planos ni temporales,
  no se borraron datos y no se inició grabación o ASR. Véase
  [`doc/evidence/sprint-4/encrypted-session-artifacts-2026-07-31.md`](doc/evidence/sprint-4/encrypted-session-artifacts-2026-07-31.md)
  y el
  [`protocolo reproducible`](doc/evidence/sprint-4/encrypted-session-artifacts-protocol.md).
- P0. Implementar recuperación idempotente tras cierre forzado o reinicio.
- P0. Implementar escrituras atómicas, checksums y limpieza controlada de temporales.
  **Completado (2026-07-31):** los artefactos cifrados sincronizan, autentican y
  sustituyen su candidato atómicamente; PCM valida offsets, tamaños y SHA-256;
  SQLCipher verifica integridad antes de promover; y la recuperación trata de
  forma idempotente temporales de escritura, migración, backup y tails GCM
  incompletos. En S25 Ultra se comprobaron 114 artefactos, 24 segmentos y cero
  temporales o archivos planos, sin grabar ni ejecutar ASR. Véase
  [`doc/evidence/sprint-4/atomic-storage-2026-07-31.md`](doc/evidence/sprint-4/atomic-storage-2026-07-31.md).
- P0. Añadir BiometricPrompt opcional para reautenticación.
- P0. Implementar borrado completo y verificable de una sesión.
  **Completado (2026-07-31):** las sesiones terminales requieren confirmación y
  se eliminan mediante rename atómico a tombstone, cascadas transaccionales e
  inspección final de archivos y relaciones. La recuperación de una
  interrupción es idempotente y una sesión activa falla cerrada. Once pruebas
  aisladas aprobaron dos veces en S25 Ultra; una auditoría posterior preservó
  intactas las 14 sesiones reales, sin grabar, transcribir ni borrar. Véase
  [`doc/evidence/sprint-4/verifiable-session-deletion-2026-07-31.md`](doc/evidence/sprint-4/verifiable-session-deletion-2026-07-31.md).
- P1. Añadir retención configurable y advertencia de consentimiento.

**Criterios de aceptación**

- Un cierre forzado durante grabación o transcripción no corrompe sesiones previas.
- La sesión incompleta puede reanudarse o finalizarse de forma segura.
- Base, audio, transcripción y notas no quedan legibles en reposo sin la clave.
- Los logs no contienen audio, transcripciones ni contenido de notas.
- El borrado elimina todos los registros y archivos asociados.
- G3 queda aprobada mediante pruebas de fallo reproducibles.

**Evidencia:** matriz de fallos, pruebas de recuperación, revisión de almacenamiento y ADR de seguridad.

---

### Sprint 5 - Gestión de sesiones, edición y exportación

**Meta:** completar el flujo de usuario desde el historial hasta un resultado editable y portable.

**Backlog prioritario**

- P0. Crear historial por fecha, duración, título y estado.
- P0. Implementar búsqueda local por título y texto.
- P0. Crear detalle de sesión con audio, transcripción y timestamps.
- P0. Permitir editar título y segmentos de transcripción.
- P0. Implementar reproducción/salto al audio desde un timestamp.
- P0. Exportar transcripción y metadatos a Markdown y TXT.
- P0. Implementar política explícita de exportación fuera del almacenamiento protegido.
- P1. Añadir etiquetas manuales.
- P1. Mejorar estados vacíos, errores y accesibilidad básica.

**Criterios de aceptación**

- Una sesión puede encontrarse por fecha, título o texto.
- Las ediciones persisten sin modificar el audio fuente.
- Pulsar un timestamp lleva al fragmento correcto con tolerancia documentada.
- Markdown y TXT se abren correctamente fuera de la app.
- La exportación requiere una acción consciente e informa la pérdida de protección local.

**Evidencia:** recorrido E2E grabar -> revisar -> editar -> buscar -> exportar.

---

### Sprint 6 - Generación local de notas estructuradas

**Meta:** generar al finalizar una nota útil, editable y respaldada por evidencia de la transcripción.

**Backlog prioritario**

- P0. Integrar Gemma 3n E2B-it mediante LiteRT-LM.
- P0. Definir esquema base: título, resumen, temas, decisiones, tareas, preguntas, eventos y destacados.
- P0. Elegir e implementar una sola plantilla profesional adicional.
- P0. Dividir la transcripción por bloques de 5 a 10 minutos o por tema.
- P0. Extraer JSON por bloque con timestamps/evidencia.
- P0. Validar esquema, fusionar, deduplicar y redactar la nota final.
- P0. Permitir editar la nota y abrir la evidencia de cada ítem relevante.
- P0. Evitar inferencias diagnósticas o afirmaciones no presentes en la transcripción.
- P1. Implementar fallback extractivo si el LLM falla, excede memoria o genera JSON inválido.

**Criterios de aceptación**

- La generación ocurre offline y solo después de finalizar la conversación.
- La app no cae ni es terminada por presión de memoria.
- Cada decisión/tarea relevante ofrece evidencia navegable o se marca como no verificada.
- La nota pasa una rúbrica de fidelidad, cobertura, organización, utilidad y editabilidad.
- Cero distorsiones de contenido sensible en el conjunto de aceptación; cualquier hallazgo bloquea G4.

**Evidencia:** conjunto de notas evaluadas, rúbrica, consumo de RAM/tiempo y ADR de modelo/pipeline.

---

### Sprint 7 - Optimización en S25 Ultra y S25+

**Meta:** seleccionar la configuración sostenible de producción en ambos dispositivos.

**Backlog prioritario**

- P0. Medir CPU-only y GPU delegate con la misma carga.
- P0. Registrar RTF, latencia p50/p95, RAM pico, batería por hora y estado térmico.
- P0. Ejecutar stress tests de 45, 60 y 90 minutos.
- P0. Ajustar concurrencia, frecuencia de parciales, tamaños de ventana y refinamientos.
- P0. Validar degradación controlada cuando la cola crece o el dispositivo se calienta.
- P0. Comparar Ultra y Plus y fijar configuración por defecto conservadora.
- P1. Explorar QNN/NPU solo si hay tiempo y un camino público reproducible.
- P1. Probar denoising solo como experimento aislado contra WER.

**Criterios de aceptación**

- No hay throttling fuerte antes de 45 minutos; objetivo: estabilidad durante 90 minutos.
- RTF sostenido es menor o igual a 1 y la latencia visible permanece menor o igual a 6 s.
- El sistema no mata el proceso y no hay jank severo por presión de memoria.
- La batería permite completar una sesión profesional de 90 minutos con margen razonable.
- GPU o NPU solo se habilitan si mejoran de forma reproducible rendimiento o energía.

**Evidencia:** informe comparativo Ultra/Plus, trazas Perfetto y ADR final de runtime/delegate.

---

### Sprint 8 - Validación final y release candidate

**Meta:** demostrar el MVP completo con sesiones reales y dejar una build liberable.

**Backlog prioritario**

- P0. Ejecutar sesiones autorizadas de 45, 60 y 90 minutos en S25 Ultra y S25+.
- P0. Evaluar WER/CER y errores de timestamps sobre el corpus congelado.
- P0. Evaluar notas con la rúbrica congelada y revisar evidencia.
- P0. Probar llamadas, background, pantalla apagada, poco espacio, permiso revocado, cierre forzado y reinicio.
- P0. Revisar consentimiento, retención, exportación y borrado.
- P0. Corregir defectos bloqueantes y ejecutar regresión completa.
- P0. Versionar corpus, ADRs, configuración de modelos y resultados.
- P0. Crear checklist de release y build firmada interna.

**Criterios de aceptación del MVP**

- Tres duraciones reales -45, 60 y 90 min- terminan sin pérdida de audio en ambos modelos.
- Tiempo a primer texto parcial menor o igual a 4 s; bloquea si supera 6 s sostenidos.
- Latencia incremental menor o igual a 6 s; bloquea si supera 8 s o el texto es muy inestable.
- RTF sostenido menor o igual a 1; bloquea si supera 1,2 en sesiones largas.
- WER espontáneo menor o igual a 22%; objetivo menor o igual a 15%; bloquea por encima de 25% en ambientes objetivo.
- No hay LMK, corrupción, pérdida de sesión ni throttling severo prematuro.
- La nota es útil, editable, fiel y trazable; cualquier distorsión sensible bloquea el release.
- Búsqueda, edición, biometría opcional y exportación Markdown/TXT funcionan de extremo a extremo.

**Entregables:** release candidate, checklist firmado, informe de validación y backlog post-MVP.

## 7. Backlog transversal

Estas tareas acompañan todos los sprints y no deben dejarse para el final:

- Actualizar ADRs ante cada decisión de modelo, runtime, formato o seguridad.
- Mantener métricas por sesión en `PerformanceMetric`.
- Registrar versión de app, modelo, cuantización, runtime, delegate y parámetros.
- Sanitizar logs y fixtures.
- Mantener corpus de desarrollo separado del corpus congelado de evaluación.
- Revisar accesibilidad, estados de error y mensajes de privacidad.
- Controlar tamaño de APK/modelos y espacio libre antes de grabar.

## 8. Registro inicial de riesgos

| Riesgo | Probabilidad | Impacto | Mitigación | Punto de decisión |
|---|---:|---:|---|---|
| ASR insuficiente en español real | Media | Alta | Comparar base/small, mejorar chunking y corpus | Sprint 2 |
| Latencia incremental excesiva | Media | Alta | Modelo menor, menos refinamiento, GPU si demuestra mejora | Sprint 3 |
| Sobrecalentamiento | Media | Alta | Reducir concurrencia; notas solo al final; degradación controlada | Sprint 7 |
| Consumo alto de batería | Media | Alta | VAD más estricto y menor frecuencia de inferencia | Sprint 7 |
| NPU inaccesible o inútil | Alta | Media | CPU/GPU como rutas soportadas | Sprint 7 |
| Corrupción o pérdida de sesión | Baja-media | Alta | Escritura atómica, checksums y recuperación idempotente | Sprint 4 |
| Nota convincente pero falsa | Media | Alta | Extracción por bloques, esquema, evidencia y revisión humana | Sprint 6 |
| Scope excesivo para una persona | Alta | Alta | Respetar exclusiones y puertas de decisión | Todos |

## 9. Ritual mínimo de sprint

- **Inicio:** seleccionar solo historias que contribuyan a la meta del sprint y registrar riesgos.
- **Diario:** revisar bloqueo técnico, cola de ASR, fallos de sesión y resultados del dispositivo real.
- **Mitad de sprint:** ejecutar una prueba integrada; no esperar al último día.
- **Cierre:** demo en dispositivo, benchmark reproducible, retro breve y decisión de puerta cuando corresponda.
- **Regla de arrastre:** una historia P0 incompleta no se declara parcialmente terminada; vuelve al backlog con causa y nueva estimación.

## 10. Primeras acciones

1. Confirmar disponibilidad física del S25 Ultra y S25+.
2. Crear repositorio/proyecto Android y automatizar el build.
3. Preparar tres audios autorizados en español: lectura limpia, conversación y ruido.
4. Descargar y registrar checksums de Whisper tiny/base cuantizados.
5. Ejecutar el spike de 3 días y decidir G0 antes de comprometer los ocho sprints.
