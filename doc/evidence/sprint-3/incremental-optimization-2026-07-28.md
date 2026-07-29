# Optimización incremental ASR — 2026-07-28

## Objetivo

Reducir trabajo repetido y presión de memoria del pipeline incremental sin
recortar calidad, y corregir la métrica de RTF antes de tomar decisiones sobre
tiny/base. La validación física de 45 minutos de G2 permanece pendiente.

## Hallazgo de medición

El evaluador sumaba `audioDurationMs` de todas las ventanas como denominador del
RTF. Como las ventanas de 4 s se emiten cada 3 s y además existen refinamientos
finales, el mismo audio aparecía varias veces en el denominador. Eso podía mostrar
un RTF artificialmente bajo.

`tools/evaluate_incremental.py` ahora:

- une los intervalos `windowStartMs..windowEndMs`;
- calcula `coveredAudioDurationMs` sin doble conteo;
- conserva `windowAudioDurationMs` para auditar cuánto solapamiento se procesó;
- informa `totalInferenceDurationMs` y `reusedResultCount`;
- calcula `weightedRealTimeFactor` como inferencia total dividida por cobertura
  temporal única;
- usa esquema de salida 4 e incluye el desglose nativo de sample, encode, decode,
  batch y prompt por inferencia, además de hipótesis suprimidas por repetición.

Una prueba de regresión demuestra el caso crítico: dos ventanas idénticas de 4 s
ya cubren 4.000 ms, no 8.000 ms. Con 5.000 ms de inferencia el RTF correcto es
1,25, no 0,625.

## Inferencia final sin trabajo duplicado

Whisper usa greedy, temperatura 0 y `no_context=true`; por lo tanto, una ventana
final exactamente igual a la última ventana parcial produce la misma hipótesis.
El coordinador ahora conserva únicamente el último resultado parcial y lo reutiliza
cuando coinciden:

- inicio de segmento;
- muestra inicial de ventana;
- muestra final de ventana.

El cierre sigue pasando por `StablePrefixReconciler.finalizeSegment`, pero evita
otra llamada nativa. La métrica final queda marcada `reusedResult=true`,
`inferenceDurationMs=0` y `realTimeFactor=0`. Si entró una sola muestra nueva, no
hay coincidencia y se ejecuta el refinamiento normal; no se reduce contexto ni se
omite audio nuevo.

La prueba determinista confirma una sola llamada al transcriptor para la secuencia
parcial→final con entrada idéntica, manteniendo ambos eventos de métrica y el texto
final.

## Pre-roll sin boxing

El pre-roll de 200 ms usaba `ArrayDeque<Short>`. Eso creaba objetos `Short` por
muestra durante silencios y podía generar GC innecesario en sesiones largas.
Ahora usa un ring buffer `ShortArray`:

- cero objetos por muestra;
- capacidad fija;
- conserva exactamente las muestras más recientes en orden;
- `clear()` no reasigna el buffer.

Las pruebas cubren wrap-around, descarte de muestras antiguas y limpieza.

## Verificación ejecutada

- `:inference-asr:testDebugUnitTest`: aprobado.
- `:inference-asr:testReleaseUnitTest`: aprobado.
- `:inference-asr:lintDebug`: aprobado.
- `python -m unittest discover -s tools/tests`: 28 pruebas aprobadas.

## Baseline físico incremental

La sesión autorizada `9f4bd2dd-98fd-4be6-bc7f-d485249f9091`, ejecutada con
`whisper-tiny-multilingual-q5_1` en el S25 Ultra, capturó 125.660 ms sin errores
de lectura, discontinuidades ni frames estimados como perdidos. Sobre 70.140 ms
de cobertura ASR produjo:

- 27 inferencias: 4 parciales y 23 finales;
- primer texto en 8.103 ms;
- latencia parcial p50 5.549 ms y p95 8.162 ms;
- 132.992 ms de inferencia total y RTF corregido 1,896;
- 21 parciales descartados y `INCREMENTAL_ASR_FINAL_QUEUE_OVERFLOW`;
- cero conflictos de prefijo estable y cero resultados reutilizados.

El resultado no supera G2. La captura no es el cuello de botella: el VAD cerró
aproximadamente un segmento cada 3 s y los finales cortos saturaron la cola.

## Segunda iteración

El timeline VAD conserva su hangover de 300 ms, pero el coordinador ASR espera
700 ms adicionales antes de materializar un final. Si vuelve a detectarse voz
durante esa gracia, cancela el cierre y continúa la misma ventana. Así se preserva
la evidencia VAD original mientras se evita invocar Whisper por pausas breves.

`incremental-transcript.json` ahora persiste también los tiempos nativos de
Whisper por inferencia para separar coste de encoder, decoder y muestreo. La
reutilización de una ventana marca esos tiempos en cero.

La sesión `b55ee116-548c-4472-9659-23e49050e843` midió 105.300 ms con captura
perfecta. Frente al baseline:

- finales: 23 → 4;
- overflow final: presente → ausente;
- RTF corregido: 1,896 → 1,468;
- inferencia total: 132.992 → 114.831 ms;
- primer texto: 8.103 → 8.433 ms;
- latencia parcial p95: 8.162 → 11.447 ms;
- descartes parciales: 21 → 14;
- conflictos de prefijo estable: 0 → 11.

La coalescencia resolvió la presión de finales y mejoró 22,6 % el RTF, pero no
alcanzó G2: el worker continuó acumulando parciales y la calidad visible siguió
siendo insuficiente.

La telemetría mostró que `encodeMs` se mantiene aproximadamente entre
60 y 90 ms, mientras la generación de tokens domina los 4–7 s de pared. Por eso
el modo incremental pasa a decodificar una sola hipótesis sin tokens de timestamp,
deshabilita los fallbacks de temperatura y limita la salida a 8 tokens por segundo
de audio (mínimo 16, máximo 32). Los timestamps de producto no cambian: proceden
del timeline PCM del coordinador. El modo offline conserva la decodificación
completa para refinamiento y métricas.

El evaluador acepta tanto JSON UTF-8 como manifiestos UTF-8 con BOM generados por
Windows PowerShell; una prueba CLI evita que la recolección verificada vuelva a
bloquear el reporte.

## Tercera medición: modo de baja latencia

La sesión `b3f5e5ab-de97-46b2-8627-2f9422624708` completó 105.960 ms con captura
perfecta y produjo:

- primer texto en 3.573 ms;
- latencia parcial p50 123 ms y p95 148,6 ms;
- inferencia p50 121 ms y p95 147,3 ms;
- RTF corregido 0,0436;
- cero descartes, cero errores técnicos y 10 conflictos de estabilización.

Los umbrales automáticos quedaron verdes, pero la revisión manual rechazó la
muestra por loops y palabras inventadas. La transcripción offline completa del
mismo audio también repitió frases, por lo que el audio ambiente no constituye
un corpus válido para decidir precisión. G2 continúa pendiente.

Antes de mostrar una hipótesis, el pipeline ahora detecta loops consecutivos de
palabras o frases y suprime los casos dominados por repetición. No intenta
"corregir" palabras: solamente evita estabilizar una salida técnicamente rápida
pero evidentemente degenerada. Cada evento conserva
`suppressedRepetition=true`; el JSON, el reporte y la UI muestran el total para
que una omisión protectora nunca sea silenciosa.

## Siguiente medición física controlada

1. Compilar e instalar la APK benchmark exacta.
2. Leer exactamente [`read-aloud-es.txt`](read-aloud-es.txt).
3. Verificar primer texto, latencia p50/p95, RTF corregido, descartes, conflictos,
   `reusedResultCount` y WER con `--reference-text`.
4. Solo después elegir tiny o base para la prueba sostenida de 45 minutos.

No se declara G2 aprobada con pruebas unitarias ni con el benchmark offline de G0.
