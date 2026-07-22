# Protocolo reproducible: laboratorio ASR en S25 Ultra

## Preparación

1. Descargar y verificar los modelos en el equipo de desarrollo:

   ```powershell
   .\tools\download-whisper-models.ps1 -Model all
   ```

2. Conectar el S25 Ultra por ADB y preparar APK y archivos:

   ```powershell
   .\tools\prepare-asr-lab.ps1
   ```

3. En la app, importar tiny y base desde `Download/NoteAppModels`. La app vuelve a
   verificar tamaño y SHA-256 antes de moverlos a almacenamiento privado.

## Sesión obligatoria

1. Grabar 5–10 minutos autorizados con lectura, conversación y un tramo de silencio.
2. Incluir al menos una pausa/reanudación y finalizar la sesión.
3. Ejecutar `Transcribir tiny` y después `Transcribir base` sin cambiar el audio.
4. Esperar que cada ejecución termine; no bloquear ni cerrar la app.

## Evidencia

Extraer desde `files/recordings/<sessionId>/`:

```powershell
.\tools\collect-device-session.ps1 `
  -SessionId <sessionId> `
  -RequireAsrModel whisper-tiny-multilingual-q5_1 `
  -RequireAsrModel whisper-base-multilingual-q5_1
```

- `checkpoint.json` y todos los `segment-NNNN.pcm` con checksums válidos.
- `vad-segments.json` con intervalos ordenados y no solapados.
- `asr-result-whisper-tiny-multilingual-q5_1.json`.
- `asr-result-whisper-base-multilingual-q5_1.json`.

Cada resultado registra modelo, chunks, timestamps, RTF, tiempo al primer texto,
PSS pico, máximo estado térmico y máxima temperatura de batería. Comparar ambos
archivos sobre la misma sesión. No adjuntar audio o transcripción sensible al repo.

## Decisión G0

- Continuar si el pipeline termina sin pérdida/desorden y el RTF es cercano o menor
  a 1 con al menos una configuración útil.
- Ajustar modelo, threads o chunks si funciona pero excede el presupuesto.
- Reestimar si falla la integración o ninguna configuración se acerca al objetivo.

Documentar dispositivo, Android, build, condiciones iniciales y decisión en un
reporte sanitizado. Sin dispositivo físico, G0 permanece pendiente.
