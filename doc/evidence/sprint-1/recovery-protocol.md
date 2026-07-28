# Protocolo reproducible: recuperación tras cierre forzado

## Objetivo

Demostrar que una grabación interrumpida por muerte del proceso reaparece al abrir
la app, que el PCM previo se valida y que la captura puede continuar sin sobrescribir
segmentos existentes.

## Preparación

1. Conectar el dispositivo por ADB, compilar e instalar la APK debug.
2. Abrir la app, conceder micrófono/notificaciones e iniciar una grabación.
3. Hablar durante al menos 60 s y confirmar que la notificación sigue activa.

## Interrupción y recuperación

La ejecución recomendada usa el arnés, que exige confirmar el `sessionId` dos veces
mediante el parámetro y el switch `-Execute` antes de realizar el cierre:

```powershell
$adb = 'C:\Users\ferna\AppData\Local\Android\Sdk\platform-tools\adb.exe'
.\tools\run-g1-recovery-case.ps1 `
  -Adb $adb `
  -Serial R5CY20HYBGJ `
  -SessionId <sessionId> `
  -Execute
```

El arnés espera 60 s de audio, ejecuta `am force-stop`, extrae una copia privada de
todos los segmentos en ese instante, abre la app y recupera con origen
`adb-harness`. Luego graba otros 60 s, pausa 10 s, reanuda, finaliza, recolecta la
sesión y compara longitud y SHA-256 de cada PCM anterior a la recuperación.

La variante manual equivalente es:

1. Sin pausar ni finalizar, ejecutar `adb shell am force-stop com.noteapp` y abrir
   Note App desde el launcher.

2. Confirmar que la pantalla muestra `Sesiones interrumpidas` y la duración del
   último checkpoint.
3. Pulsar `Reanudar`, grabar otros 60 s, pausar, reanudar una vez y finalizar.
4. No modificar manualmente los archivos privados durante el caso válido.

## Evidencia a comprobar

En `files/recordings/<sessionId>/`:

- El segmento presente durante el crash fue adoptado después de comprobar longitud
  PCM par y calcular SHA-256.
- La captura recuperada usa el siguiente `segment-NNNN.pcm`; ningún archivo previo
  fue truncado o sobrescrito.
- `checkpoint.json` termina en `COMPLETED`, con offsets contiguos, tamaños reales y
  checksums válidos.
- `vad-segments.json` mantiene secuencias y offsets crecientes. El audio posterior
  al último timeline persistido fue reprocesado antes de reanudar la captura.
- La notificación vuelve a reflejar `RECORDING` y responde a pausa/finalización.

Registrar build, dispositivo, Android, duración y resultado sin copiar audio o
transcripción sensible al repositorio.

Tras finalizar, ejecutar:

```powershell
.\tools\collect-device-session.ps1 -SessionId <sessionId>
```

`verification.json` debe confirmar offsets/checksums contiguos y contadores de
discontinuidad. El archivo privado extraído permite comprobar además que ningún
segmento previo fue reemplazado.

## Caso negativo

En una copia de laboratorio, alterar un byte de un segmento listado y repetir la
recuperación. La app debe rechazarla con `AUDIO_RECOVERY_FAILED`; nunca debe anexar
audio a una cadena cuyo checksum no coincida.
