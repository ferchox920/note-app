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
- usa esquema de salida 2.

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

## Siguiente medición física

1. Compilar e instalar la APK benchmark exacta.
2. Ejecutar una sesión corta comparable con tiny y habla autorizada.
3. Verificar primer texto, latencia p50/p95, RTF corregido, descartes, conflictos y
   `reusedResultCount`.
4. Solo después elegir tiny o base para la prueba sostenida de 45 minutos.

No se declara G2 aprobada con pruebas unitarias ni con el benchmark offline de G0.
