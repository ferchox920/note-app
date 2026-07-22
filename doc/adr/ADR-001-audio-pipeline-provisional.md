# ADR-001: Pipeline de audio provisional para el spike

- Estado: provisional; `direct-16k` validado en G0, comparación 48→16 pendiente
- Fecha: 2026-07-22

## Contexto

El DocMaster exige comparar captura directa a 16 kHz con captura nativa a 48 kHz
y resampleo a 16 kHz. También exige que el formato final se decida con evidencia,
no por preferencia. Para iniciar el spike hace falta una ruta baseline que produzca
PCM consumible por VAD y Whisper.

## Decisión provisional

- `AudioRecord` con fuente `MIC`.
- PCM16 mono a 16 kHz.
- Foreground service declarado como `microphone`.
- Un archivo PCM crudo por tramo entre inicio/reanudación y pausa/finalización.
- `fsync` al cerrar cada tramo, checksum SHA-256 y `checkpoint.json` reemplazado
  atómicamente.
- Duración calculada desde bytes PCM persistidos, no desde reloj de pared.
- Ningún audio o texto se registra en logs.
- WebRTC VAD aislado en `inference-vad`, usando frames de 320 muestras/20 ms,
  modo `AGGRESSIVE`, onset mínimo de 60 ms, pre-roll de 200 ms y hangover de
  300 ms.
- Los segmentos de voz se persisten en `vad-segments.json` con timestamps y
  offsets sobre la concatenación lógica de PCM.
- Al reiniciar la app se descubren checkpoints en `RECORDING`, `PAUSED` o
  `RECOVERING`. La recuperación valida formato, tamaño y SHA-256 de cada segmento,
  adopta un último PCM huérfano válido dejado por el crash y continúa con el
  siguiente número de secuencia.
- Antes de volver a capturar, VAD reprocesa el PCM posterior al último
  `processedDurationMs` persistido y retoma secuencias/offsets existentes.
- La APK de laboratorio expone dos rutas comparables: `direct-16k` y
  `native-48k-to-16k`. La segunda aplica un FIR low-pass de 63 taps y decimación
  3:1; ambas persisten PCM16 mono a 16 kHz y alimentan el mismo VAD/ASR.

Los archivos se almacenan en el directorio interno privado:
`files/recordings/<sessionId>/segment-NNNN.pcm`.

## Alternativas pendientes

- Selección definitiva entre 16 kHz directo y 48 kHz con remuestreo; ambas rutas
  están implementadas, pero falta medirlas en el S25.
- Fuente `UNPROCESSED` o `VOICE_RECOGNITION` cuando el dispositivo la soporte.
- Archivo maestro WAV o contenedor comprimido además de PCM temporal.
- Tamaños de buffer alternativos medidos por discontinuidades, energía y calidad.
- Comparación WebRTC vs. Silero sobre un subconjunto fijo.

## Dependencia VAD fijada

- Artefacto: `com.cloudflare.realtimekit.android-vad:webrtc:2.0.10-cf.4`.
- Upstream: `gkonovalov/android-vad`, licencia MIT archivada en
  `doc/licenses/android-vad-MIT.md`.
- SHA-256 AAR debug: `eb528ce0b6e737b918a3e62e2be377139f04c62d507a2331a984a1140b7e48b7`.
- SHA-256 AAR release: `6023e75983b59796cc3bb64078b378f7e94046b257b14f5688c6c86ed130ebde`.

Para la comparación de Sprint 2 también se fija:

- Silero: `com.cloudflare.realtimekit.android-vad:silero:2.0.10-cf.4`.
- SHA-256 AAR Silero: `c3d031584bce1a20eea43a34a9eab94edd1c3559667b8a7450122f6acebe672c`.
- Runtime transitivo: `com.microsoft.onnxruntime:onnxruntime-android:1.22.0`.
- Licencias MIT archivadas en `doc/licenses/android-vad-MIT.md` y
  `doc/licenses/onnxruntime-MIT.md`.

## Criterio para aceptar o reemplazar

Esta decisión no se acepta definitivamente hasta comparar ambas frecuencias en el
S25 Ultra con los mismos audios y registrar estabilidad, bytes esperados, WER,
energía y temperatura. G0 validó el baseline directo, no esa comparación P1.

## Evidencia física G0

La ruta `direct-16k` completó 6:18 con pausa/reanudación y ochenta segundos de
pantalla apagada. Persistió 12.108.800 bytes en tres PCM con checksums válidos,
127 segmentos WebRTC VAD, cero errores, cero discontinuidades y cero frames
faltantes estimados. Esto valida la integridad del baseline, pero no sustituye la
comparación controlada contra `native-48k-to-16k`; la elección definitiva sigue
pendiente.
