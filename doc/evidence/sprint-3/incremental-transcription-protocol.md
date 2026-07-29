# Protocolo: transcripción incremental estable

G2 permanece **pendiente**. Este documento fija cómo medir la implementación y no
reemplaza G0/G1 en el dispositivo objetivo.

## Configuración inicial

- PCM de entrada: mono, PCM16, 16 kHz después de normalización.
- Ventana de Whisper: hasta 4 s.
- Emisión parcial: cada 3 s de habla acumulada.
- Overlap entre ventanas llenas: 1 s.
- Cola de inferencia: máximo 2 ventanas; si se llena, descartar la más antigua y
  contar el descarte.
- Estabilización: confirmar palabras compartidas por dos hipótesis consecutivas y
  conservar al menos una palabra como cola provisional.
- Cierre: refinamiento final al endpoint VAD o al límite de duración del segmento.
- Si la ventana final coincide exactamente con la última parcial, reutilizar la
  hipótesis determinista y registrar `reusedResult=true`; cualquier audio nuevo
  obliga a ejecutar el refinamiento.

## Estado implementado

- El foreground service alimenta al coordinador desde el mismo PCM normalizado que
  se escribe y procesa con VAD.
- El modelo incremental es opt-in por sesión; una carga o inferencia fallida no
  interrumpe AudioRecord.
- La UI distingue el prefijo estable de la cola provisional y muestra timestamps de
  segmentos finalizados, cola, descartes, conflictos, primer texto, latencia y RTF.
- `incremental-transcript.json` conserva segmentos y métricas mediante reemplazo
  atómico. Al recuperar se restaura solo texto ya finalizado.
- La política completa está fijada en
  [`../../adr/ADR-003-incremental-asr-lifecycle.md`](../../adr/ADR-003-incremental-asr-lifecycle.md).

## Pruebas deterministas

```powershell
.\gradlew.bat `
  :inference-asr:testDebugUnitTest `
  :inference-asr:testReleaseUnitTest `
  :inference-asr:lintDebug
```

Las pruebas cubren invariancia frente a los límites de buffer, offsets y overlap,
descarte bajo backpressure, ausencia de duplicación del prefijo y cambios permitidos
solamente en la cola provisional. También cubren prioridad de finales, overflow
explícito, ciclo parcial→final y persistencia del modelo elegido en el checkpoint.

Cada inferencia persiste inicio/fin de ventana, tipo parcial/final, duración de audio,
tiempo de inferencia, latencia visible, RTF, tiempos nativos de Whisper y si
reutilizó un resultado idéntico.
El RTF sostenido se calcula contra la unión temporal de las ventanas, no contra la
suma que contaría el overlap varias veces. Después de copiar exclusivamente los
JSON autorizados a un directorio privado, generar percentiles con:

```powershell
python .\tools\evaluate_incremental.py `
  --results-dir .\artifacts\private\incremental-sessions `
  --output-dir .\artifacts\private\incremental-evaluation `
  --consent-confirmed
```

La salida `incremental-evaluation.json`/CSV agrupa por modelo y pipeline, informa
p50/p95, peor sesión, descartes y conflictos. `eligibleForManualG2Review` significa
solamente que supera umbrales automáticos; nunca aprueba G2 sin revisar estabilidad
de captura y duplicaciones.

## Prueba en dispositivo para G2

1. Cerrar primero G0 y G1 en S25 Ultra; repetir luego en S25+.
2. Grabar una sesión autorizada de 45 minutos con pantalla apagada y combinación de
   habla, pausas y ruido del entorno objetivo.
3. Archivar por hipótesis: inicio/fin de audio, inicio/fin de inferencia, modelo,
   ventana, longitud de cola y descartes acumulados.
4. Calcular tiempo a primer parcial y latencia visible p50/p95.
5. Archivar RTF, PSS pico, estado térmico, temperatura y discontinuidades de audio.
6. Revisar manualmente duplicaciones o reescrituras del prefijo estable.
7. Verificar que `incremental-transcript.json` corresponda a lo mostrado y que una
   recuperación conserve los segmentos finalizados sin restaurar una cola inestable.

Extraer y validar la evidencia desde la APK debug con:

```powershell
.\tools\collect-device-session.ps1 `
  -SessionId <sessionId> `
  -RequireIncremental
```

El reporte resultante omite contenido hablado. El directorio privado extraído sí
contiene PCM/transcripciones y está excluido mediante `.gitignore`.

G2 exige primer parcial menor o igual a 4 s, latencia visible menor o igual a 6 s,
RTF sostenido menor o igual a 1, ausencia de duplicaciones graves y captura estable
bajo carga. Si la cola descarta de forma sostenida, se debe reducir modelo/frecuencia
o refinamiento antes de aprobar la puerta.
