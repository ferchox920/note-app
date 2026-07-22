# Protocolo reproducible: WebRTC VAD vs. Silero VAD

## Implementaciones fijadas

- WebRTC: `AGGRESSIVE`, frames de 320 muestras/20 ms.
- Silero: `NORMAL`, frames de 512 muestras/32 ms, ONNX Runtime Mobile CPU.
- Endpointing común: onset 60 ms, pre-roll 200 ms y hangover 300 ms.

Ambos procesan offline los mismos `segment-NNNN.pcm` normalizados a 16 kHz. La APK
genera `vad-comparison-webrtc-vad.json`, `vad-comparison-silero-vad.json` y un
resumen `vad-comparison.json` sin modificar el timeline usado durante la captura.

## Procedimiento

1. Finalizar una sesión autorizada con habla, pausas, ruido y silencio.
2. Pulsar `Comparar WebRTC / Silero` y esperar ambos resultados.
3. Verificar timelines ordenados, cobertura, cantidad de cortes y RTF.
4. Comparar los intervalos con anotaciones humanas del subconjunto fijo; cobertura
   por sí sola no demuestra precisión.
5. Repetir en ambas rutas de frecuencia de captura.

Renombrar cada timeline como
`<item-id>--<engine>--<capture-pipeline>.json`, colocarlo en
`corpus/private/vad-timelines/` y ejecutar:

```powershell
python .\tools\evaluate_vad.py `
  --manifest .\corpus\private\manifest.json `
  --timelines-dir .\corpus\private\vad-timelines `
  --output-dir .\artifacts\private\vad-evaluation
```

El manifiesto debe tener `consentConfirmed: true`; audio, anotaciones y resultados
reales permanecen fuera de Git.

## Decisión

Seleccionar el detector con menor pérdida de habla y menos activaciones en ruido,
siempre que su costo de RAM/RTF/temperatura sea aceptable. Silero añade ONNX Runtime
y eleva el APK de laboratorio, por lo que una mejora pequeña no basta para adoptarlo.
