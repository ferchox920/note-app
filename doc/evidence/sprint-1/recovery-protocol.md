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

El arnés espera 60 s de audio, ejecuta `am force-stop` y usa el proveedor
exclusivo de la build debug para autenticar el prefijo recuperable. El proveedor
es `exported=false`, acepta solo llamadas del mismo UID y devuelve únicamente
estado, duración y contadores; nunca audio ni texto. A continuación extrae una
copia privada del **ciphertext** de los segmentos, abre la app y recupera con
origen `adb-harness`. Luego graba otros 60 s, pausa 10 s, reanuda, finaliza y
compara longitud y SHA-256 del ciphertext de cada segmento previo.

La variante manual equivalente es:

1. Sin pausar ni finalizar, ejecutar `adb shell am force-stop com.noteapp` y abrir
   Note App desde el launcher.

2. Confirmar que la pantalla muestra `Sesiones interrumpidas` y la duración del
   último checkpoint.
3. Pulsar `Reanudar`, grabar otros 60 s, pausar, reanudar una vez y finalizar.
4. No modificar manualmente los archivos privados durante el caso válido.

## Evidencia a comprobar

En `files/recordings/<sessionId>/`:

- El segmento presente durante el crash descartó, como máximo, un frame final
  incompleto; todos los frames conservados autenticaron con AES-GCM antes de
  adoptar el prefijo.
- La captura recuperada usa el siguiente `segment-NNNN.pcm`; ningún archivo previo
  fue truncado o sobrescrito.
- `checkpoint.json` termina en `COMPLETED`, con offsets contiguos, tamaños reales y
  checksums válidos.
- `vad-segments.json` mantiene secuencias y offsets crecientes. El audio posterior
  al último timeline persistido fue reprocesado antes de reanudar la captura.
- La notificación vuelve a reflejar `RECORDING` y responde a pausa/finalización.

Registrar build, dispositivo, Android, duración y resultado sin copiar audio o
transcripción sensible al repositorio.

Tras finalizar, el mismo arnés ejecuta
`tools/verify-s4-encrypted-artifacts.ps1`. La prueba instrumentada descifra dentro
del proceso de la app, valida offsets, longitudes, SHA-256, etiquetas GCM,
permisos, temporales e idempotencia, y solo emite un resumen sin contenido. Los
archivos privados extraídos permanecen cifrados y permiten comprobar además que
ningún segmento previo fue reemplazado.

## Caso negativo

En una copia de laboratorio, alterar un byte de un frame completo de un segmento
listado y repetir la recuperación. La app debe rechazarla con
`AUDIO_RECOVERY_FAILED`; únicamente un tail físicamente incompleto puede
descartarse. Nunca debe anexar audio a una cadena cuya etiqueta o checksum no
coincida.
