# Protocolo reproducible: G1 captura fiable

## Objetivo

Demostrar en el Galaxy S25 Ultra que la captura PCM16 mono permanece íntegra durante
90 minutos con la pantalla apagada, que pausa/reanudación crea una transición
controlada, y que una sesión interrumpida puede recuperarse sin sobrescribir audio.

G1 se ejecuta sin ASR incremental para aislar el ciclo de vida de captura. La
transcripción concurrente pertenece a G2.

## Preparación

Con el teléfono conectado, autorizado y sin una sesión recuperable pendiente:

```powershell
$adb = 'C:\Users\ferna\AppData\Local\Android\Sdk\platform-tools\adb.exe'
.\tools\prepare-g1-device-test.ps1 `
  -Adb $adb `
  -Serial R5CY20HYBGJ `
  -Install `
  -Launch
```

El script ejecuta las pruebas de dominio, almacenamiento y audio, lint de la
variante `benchmark`, instala con `-r` para conservar modelos/sesiones y muestra el
hash exacto de la APK. No continuar si detecta una sesión recuperable anterior.

Registrar antes de cada caso: hora, batería, versión de Android, versión/hash de la
APK y espacio libre. El audio y los checkpoints completos permanecen en
`artifacts/private/`; al repositorio solo llega evidencia sanitizada.

## Caso A: 90 minutos, pantalla apagada y pausa

1. En la app seleccionar **Sin ASR en vivo** e **Iniciar 16 kHz**.
2. Confirmar estado `RECORDING`, notificación persistente y copiar el `sessionId`.
   Iniciar el monitor sanitizado en segundo plano si se desea evidencia por minuto:

   ```powershell
   .\tools\monitor-g1-device-session.ps1 `
     -Adb $adb `
     -Serial R5CY20HYBGJ `
     -SessionId <sessionId>
   ```

3. Apagar la pantalla durante al menos 80 de los 90 minutos.
4. Entre los minutos 30 y 35, despertar el teléfono, pausar durante 10 segundos y
   reanudar. Registrar las horas de ambas acciones y volver a apagar la pantalla.
5. Entre los minutos 60 y 65, abrir otra app durante un minuto sin cerrar Note App;
   comprobar que la notificación continúa y volver a apagar la pantalla.
6. Al superar 90 minutos de audio útil, tocar **Finalizar**.
7. Exigir estado `COMPLETED`, duración de al menos 5.400.000 ms, cero errores de
   lectura, cero discontinuidades y cero frames estimados perdidos.

La pausa no cuenta como audio útil. Su pérdida esperada es exclusivamente el
intervalo comprendido entre pulsar **Pausar** y **Reanudar**.

## Caso B: cierre forzado y recuperación

Ejecutar el procedimiento de
[`recovery-protocol.md`](recovery-protocol.md) sobre una sesión nueva. Debe contener
audio antes y después del cierre, al menos una pausa posterior y terminar
`COMPLETED`. La recuperación debe adoptar el segmento huérfano validando longitud y
SHA-256, y continuar con el siguiente número de segmento.

## Caso C: interrupción por llamada

1. Iniciar una sesión nueva, apagar la pantalla y grabar al menos 60 segundos.
2. Recibir una llamada de prueba, atender durante 15–30 segundos y finalizarla.
3. Registrar el estado observado: continuidad automática, error recuperable o
   sesión recuperable al reabrir.
4. Si Android retira el micrófono, reabrir Note App y usar **Reanudar**; grabar
   otros 60 segundos y finalizar.
5. Nunca considerar correcto un reinicio silencioso que oculte un hueco: cualquier
   discontinuidad o frames perdidos debe quedar contabilizado.

La llamada es un caso separado porque el sistema puede reservar el micrófono para
telefonía. G1 exige que el comportamiento sea explícito y recuperable, no que se
grabe el audio de la llamada.

## Recolección y criterios

Para cada sesión finalizada:

```powershell
.\tools\collect-device-session.ps1 `
  -Adb $adb `
  -Serial R5CY20HYBGJ `
  -SessionId <sessionId>
```

La evidencia privada debe contener `verification.json` válido. El informe
sanitizado de G1 debe registrar:

- build, dispositivo y configuración;
- duración útil y tiempo con pantalla apagada;
- bytes esperados frente a bytes reales;
- número de segmentos y validación SHA-256;
- errores de lectura, discontinuidades y frames estimados perdidos;
- comportamiento de notificación, pausa, background, cierre forzado y llamada;
- riesgos observados y decisión explícita `CONTINUAR`, `AJUSTAR` o `DETENER`.

G1 solo se aprueba si el caso A cumple 90 minutos sin pérdida/corrupción, el caso B
recupera sin sobrescritura y los tres casos dejan un resultado reproducible. Un
fallo en llamada puede ser aceptable únicamente si se informa y permite recuperar
la sesión sin corrupción; un hueco no contabilizado bloquea la puerta.
