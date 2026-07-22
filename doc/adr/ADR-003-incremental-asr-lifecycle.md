# ADR-003: ciclo de vida del ASR incremental

- Estado: aceptado provisionalmente; validar en G2
- Fecha: 2026-07-21

## Contexto

La transcripción debe continuar con pantalla apagada y no puede depender de que una
pantalla Compose o un `ViewModel` permanezcan observando PCM. Whisper es
pseudo-streaming: cada inferencia reprocesa una ventana y puede tardar más que el
intervalo de emisión.

## Decisión

- El coordinador incremental vive con `AudioCaptureService`, bajo el mismo
  `CoroutineScope` supervisado del foreground service.
- `AudioRecord` solamente decodifica/copia la ventana y encola trabajo; whisper.cpp
  corre secuencialmente fuera del bucle de captura.
- La cola admite dos trabajos. Se descartan parciales antiguos primero; un cierre
  final nunca desplaza silenciosamente a otro cierre final y un overflow se registra.
- WebRTC VAD controla onset/endpoint. Sin VAD, el PCM continúa grabándose y el ASR
  incremental informa un error recuperable.
- El modelo se elige explícitamente antes de iniciar y debe estar instalado y
  verificado. El error de carga no cancela la grabación.
- Pausa y finalización fuerzan refinamiento del segmento. Al completar se drena la
  cola; al abortar/fallar se prioriza cerrar y recuperar la captura.
- Los segmentos finalizados y métricas se reemplazan atómicamente en
  `incremental-transcript.json`. La recuperación descarta solamente la hipótesis
  provisional interrumpida y conserva segmentos finalizados.

## Consecuencias

La captura no queda ligada al ciclo de vida visual y la presión de inferencia es
observable y acotada. El costo es que `core-audio` depende de la API de
`inference-asr`; si futuros motores requieren procesos o servicios separados, esa
frontera deberá extraerse detrás de una interfaz de pipeline.

La decisión no aprueba G2: RTF, latencia, temperatura y estabilidad de 45 minutos
deben medirse en S25 Ultra/S25+.
