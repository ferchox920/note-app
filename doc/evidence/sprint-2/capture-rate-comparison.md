# Protocolo reproducible: 16 kHz directo vs. 48 kHz -> 16 kHz

## Hipótesis

La ruta nativa de 48 kHz puede mejorar estabilidad o calidad en el S25, pero añade
costo de CPU y remuestreo. Ambas rutas deben terminar en el mismo PCM16 mono de
16 kHz para que VAD, chunking y ASR sean comparables.

## Implementaciones

- `direct-16k`: AudioRecord solicitado a 16.000 Hz; PCM escrito sin remuestreo.
- `native-48k-to-16k`: AudioRecord solicitado a 48.000 Hz; FIR low-pass de 63 taps
  con corte de 7,2 kHz y decimación exacta 3:1 antes de persistir/VAD/ASR.

El checkpoint registra `capturePipeline`, `captureSampleRateHz` y formato de salida.
El JSON ASR copia `capturePipelineId`; el evaluador separa cada combinación
modelo/pipeline.

## Procedimiento

1. Reproducir desde un altavoz externo el mismo material autorizado, manteniendo
   distancia y volumen fijos.
2. Grabar una sesión con `Iniciar 16 kHz` y otra con `Iniciar 48→16 kHz`.
3. Repetir lectura, conversación controlada, ruido y silencio, alternando el orden
   para reducir sesgo térmico.
4. Transcribir ambas sesiones con el mismo modelo y parámetros.
5. Nombrar resultados como
   `<item-id>--<capture-pipeline>--<model-id>.json` y ejecutar `evaluate_asr.py`.

## Decisión

Comparar WER/CER, errores de lectura, discontinuidades, RTF, PSS, batería y estado
térmico. Conservar 48 kHz solamente si la mejora observada justifica su costo; si
no, aceptar 16 kHz directo como ruta inicial. No decidir con tests sintéticos.
