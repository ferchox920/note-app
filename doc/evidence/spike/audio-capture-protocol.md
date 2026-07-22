# Protocolo reproducible: captura AudioRecord

## Objetivo

Validar que el baseline PCM16 mono a 16 kHz graba, pausa y reanuda sin corrupción,
primero durante 5–10 minutos y luego con una prueba extendida de pantalla apagada.

## Preparación

1. Conectar el Galaxy S25 Ultra por ADB y confirmar que no haya audios sensibles
   de pruebas anteriores.
2. Compilar e instalar:

   ```powershell
   .\gradlew.bat :app:assembleDebug
   adb install -r app\build\outputs\apk\debug\app-debug.apk
   adb shell am start -n com.noteapp/.MainActivity
   ```

3. Conceder micrófono y notificaciones desde la UI. El servicio de micrófono debe
   iniciarse desde la actividad visible; no intentar iniciarlo desde background.

## Caso corto obligatorio

1. Grabar 2 minutos de lectura autorizada.
2. Pausar 10 segundos.
3. Reanudar y grabar otros 3 minutos.
4. Apagar la pantalla durante al menos 60 segundos.
5. Encender la pantalla y finalizar.
6. Confirmar que la notificación cambia entre grabando y pausado.

## Evidencia a extraer

Copiar el ID seleccionable que muestra la app y extraer/verificar la sesión hacia el
directorio privado ignorado por Git:

```powershell
.\tools\collect-device-session.ps1 -SessionId <sessionId>
```

El script usa `run-as` sobre la APK debug, rechaza entradas inseguras del archivo y
ejecuta `verify_session_artifacts.py`. También puede inspeccionarse con Device
Explorer sobre `/data/data/com.noteapp/files/recordings/<sessionId>/`.

- `checkpoint.json` con estado `COMPLETED`.
- Dos segmentos PCM con SHA-256 coincidente con el checkpoint.
- `totalBytes` par y duración consistente con 32.000 bytes por segundo.
- Build exacta, modelo de dispositivo y versión Android.
- Cualquier error de lectura o interrupción observada, sin copiar contenido sensible
  a logs.

Conservar `verification.json` como evidencia sanitizada. La carpeta `session/` y
`session.tar` contienen audio/transcripciones y nunca se deben versionar ni compartir
sin autorización explícita.

## Casos posteriores

- Cierre forzado mientras graba: el checkpoint previo debe permitir detectar la
  sesión incompleta; la recuperación completa se implementa en el siguiente corte.
- Llamada entrante y pérdida temporal de foco de audio.
- Poco espacio disponible.
- Prueba de 90 minutos con pantalla apagada para la puerta G1.
