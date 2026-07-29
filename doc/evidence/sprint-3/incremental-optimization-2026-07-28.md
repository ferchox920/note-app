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

## Lectura física controlada

La sesión `282e9873-1c38-4ca7-b9a1-846ee89f37e2` leyó
[`read-aloud-es.txt`](read-aloud-es.txt) en el S25 Ultra. La captura completó
111.720 ms sin errores de lectura, discontinuidades, frames estimados como
perdidos ni errores del pipeline.

El perfil incremental tiny de baja latencia produjo:

- 39 inferencias: 35 parciales y 4 finales;
- primer texto en 3.163 ms;
- latencia visible p50 127 ms y p95 154,1 ms;
- inferencia p50 122 ms y p95 147,2 ms;
- RTF corregido 0,0422;
- cero descartes, cero conflictos y cero errores técnicos;
- 25 de 39 hipótesis suprimidas por repetición;
- WER 90,45 %.

El rendimiento supera holgadamente los límites de G2, pero la calidad lo invalida.
La supresión evitó mostrar varios loops, aunque no puede convertir una hipótesis
degenerada en texto útil.

## Comparación offline sobre el mismo audio

Sin volver a grabar ni cambiar el corpus se ejecutaron los perfiles offline:

| Perfil | Bloque | WER | RTF | Primer texto |
|---|---:|---:|---:|---:|
| tiny q5_1 completo | 30 s | 30,34 % | 0,0476 | 1.475 ms |
| base q5_1 completo | 30 s | 21,91 % | 0,0998 | 2.985 ms |
| tiny q5_1, contexto recortado | 10 s | 82,02 % | 0,1575 | 5.961 ms |
| tiny q5_1, contexto de encoder 15 s | 10 s | 30,34 % | 2,3069 | 34.168 ms |
| tiny q5_1, contexto completo 30 s | 10 s | 28,65 % | 3,9364 | 22.177 ms |

La variante base de 30 s entra en el límite de WER aceptable del documento
maestro y conserva RTF menor que 1, por lo que es una candidata válida para el
refinamiento final. El experimento de contexto completo consumió 427.497 ms para
108.600 ms de audio; 405.661 ms correspondieron al encoder. El contexto de 15 s
redujo el coste, pero no lo suficiente. Ambos cambios experimentales se
revirtieron después de medirlos.

## Decisión

Whisper tiny/base con ventanas pseudo-streaming cortas no ha demostrado todavía
una configuración que cumpla simultáneamente calidad y tiempo real:

- recortar el contexto permite RTF menor que 1, pero destruye la precisión;
- conservar suficiente contexto recupera un WER cercano al offline, pero supera
  RTF 2;
- el modo sin timestamps reduce la latencia a cientos de milisegundos, pero
  degenera en repeticiones sobre el corpus controlado.

El perfil rápido se conserva únicamente como línea base protegida y observable;
no se declara G2 aprobada. El refinamiento final debe usar base q5_1 en bloques
de hasta 30 s. El siguiente experimento de transcripción incremental debe evaluar
un backend realmente streaming —la ruta de mitigación prevista es Sherpa-ONNX—
y compararlo contra esta misma lectura antes de iniciar la prueba sostenida de
45 minutos.

## Cuarta iteración: transductor streaming en español

El 2026-07-29 se integró Sherpa-ONNX 1.13.4 con el modelo experimental
`sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06`, fijado a la revisión
`20cf7a4921613397841d31168796cade5b866585`. El instalador verifica tamaño y
SHA-256 de encoder, decoder, joiner y vocabulario antes y después de copiarlos al
directorio privado de la APK. El AAR también queda cubierto por verificación de
dependencias de Gradle.

La licencia del motor Sherpa-ONNX es Apache-2.0, pero la licencia exacta del
modelo no se considera resuelta: su tarjeta remite de forma genérica a modelos
comunitarios CC-BY-SA mientras los metadatos del repositorio indican `test` y no
especifican versión. Por eso el catálogo lo marca
`UNRESOLVED_EXPERIMENT_ONLY`; no puede distribuirse en producción hasta completar
la revisión.

### Replay físico sobre el corpus congelado

La sesión `282e9873-1c38-4ca7-b9a1-846ee89f37e2` se reprodujo continuamente,
sin reiniciar el estado entre tramas:

- WER: 20,22 % sobre 178 palabras de referencia;
- primer texto: 1.500 ms de audio;
- RTF: 0,0257;
- 83 actualizaciones parciales, 7 endpoints y 8 segmentos finalizados;
- PSS pico: 397.457 KiB;
- estado térmico máximo: 0;
- temperatura máxima de batería: 25,1 °C.

Esta es la primera configuración incremental medida que combina WER menor o igual
a 22 %, primer texto menor o igual a 4 s y RTF menor que 1 sobre el corpus
congelado.

### Lectura física en vivo

La sesión autorizada `4fc280eb-30a6-476a-adb4-577be8dec1d5`, capturada con la
integración real del foreground service, completó 137.940 ms:

- cero errores de lectura, discontinuidades o frames estimados perdidos;
- cero parciales descartados, conflictos estables o errores del ASR;
- 87 cambios parciales y 10 segmentos finalizados;
- latencia de cola p50 0 ms y p95 1 ms;
- RTF sostenido 0,0900;
- WER bruto de sesión completa 24,72 %;
- WER del tramo de lectura controlada 11,80 %: 21 errores sobre 178 palabras.

La grabación ya estaba transcribiendo antes de que comenzara el texto de
referencia. El evaluador v5 conserva el WER bruto y añade una alineación
semiglobal: excluyó 23 palabras iniciales externas al texto y ninguna final. Esta
métrica solo se usa con una lectura controlada; no perdona inserciones dentro del
tramo alineado. Su implementación usa memoria O(n) respecto de la hipótesis.

El contador original dio 11.860 ms desde que se pulsó «Iniciar». La primera
hipótesis pertenecía al segmento nativo iniciado en 10.400 ms, por lo que el
tiempo algorítmico derivado desde el comienzo de esa frase fue 1.460 ms. La
implementación posterior registra esta latencia relativa al segmento para no
confundir preparación humana con retraso del decodificador.

La calidad del tramo controlado queda dentro del máximo aceptable de 22 %. Sherpa
se acepta provisionalmente para parciales visibles y para finalizar segmentos,
manteniendo el 24,72 % bruto como dato de auditoría de la sesión completa.

### Optimización posterior a la lectura

AudioRecord entregó unas 6.898 lecturas de aproximadamente 20 ms y el primer
adaptador creó una métrica por cada una. El JSON incremental alcanzó 2.033.467
bytes en solo 2 min 17 s. El adaptador ahora:

- acumula PCM continuo en tramas de 100 ms antes de llamar al transductor;
- drena una última trama parcial al completar la sesión;
- conserva el timeline PCM autoritativo;
- mantiene una cola acotada de 64 tramas y error explícito de overflow;
- registra el primer texto relativo al inicio del segmento nativo;
- tiene pruebas deterministas para coalescencia, endpoints, drenaje y cierre.

Esto reduce aproximadamente cinco veces el número de decodificaciones, emisiones
de estado y filas de telemetría sin recortar audio ni reiniciar el contexto.

El smoke test físico posterior, sesión
`d24dee16-cff9-42ed-8b0c-ff0dd6ddbd63`, confirmó la reducción: 24.260 ms
produjeron 243 métricas (10,0/s), frente a 50,0/s en la lectura anterior. También
obtuvo primer texto en 2.800 ms, RTF 0,0479, cero descartes, cero discontinuidades
y cero errores técnicos.

Un intento de repetir Whisper base inmediatamente después de la lectura fue
cancelado de forma segura: superó tres minutos con ocupación sostenida de varios
núcleos y la piel llegó a 37,9 °C, cerca del primer umbral térmico de 38 °C. No
produjo resultado y no se usa como benchmark. La repetición posterior con el
teléfono frío también superó RTF 1 sin producir resultado. Se probaron
diagnósticamente límites de fallback, tokens y chunks más cortos; tampoco
garantizaron finalización y todos esos cambios se revirtieron. El WER 21,91 % /
RTF 0,100 anterior fue válido para aquel PCM congelado, pero no demuestra un
coste acotado para entradas arbitrarias.

## Decisión actualizada

- Sherpa-ONNX Zipformer queda como backend incremental experimental seleccionado.
- El texto final provisional es el segmento cerrado por endpointing de Sherpa.
- Whisper base q5_1 queda pospuesto hasta disponer de un refinamiento cancelable,
  acotado y validado sobre entradas difíciles; no se ejecuta automáticamente.
- G2 no se aprueba todavía: faltan 45 minutos continuos, revisión manual de
  estabilidad, medición de memoria/batería/termal en vivo y repetición en S25+.
- La distribución del modelo Sherpa queda bloqueada hasta resolver su licencia
  exacta.
