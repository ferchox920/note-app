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

3. Dejar que la pantalla se apague por su timeout normal y mantenerla apagada
   durante al menos 80 de los 90 minutos. No inyectar `KEYCODE_POWER`/`KEYCODE_SLEEP`
   mediante ADB: en este S25 el evento sintético puede activar el botón Compose que
   conserva el foco y no representa una interacción física válida.
4. Entre los minutos 30 y 35, pausar durante 10 segundos y reanudar. Se puede usar
   la notificación físicamente o el arnés ADB con origen `adb-harness`; registrar
   las horas y el origen de ambas acciones. Si se despertó el teléfono físicamente,
   dejar que vuelva a apagarse por timeout.
5. Entre los minutos 60 y 65, abrir otra app durante un minuto sin cerrar Note App;
   comprobar que la notificación continúa y volver a apagar la pantalla.
6. Al superar 90 minutos de audio útil, tocar **Finalizar** y confirmar en el
   diálogo. En una ejecución desatendida se permite `ACTION_COMPLETE` desde el
   arnés después de comprobar el umbral, con origen inmutable `adb-harness`. El
   diálogo sigue siendo obligatorio para el cierre desde UI y evita accidentes.
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

En Android 10 o superior la app observa `AudioRecordingConfiguration.isClientSilenced`.
Si una captura de mayor prioridad silencia el cliente, debe cerrar el segmento,
persistir `RECOVERING` con `AUDIO_CLIENT_SILENCED`, incrementar el contador de
discontinuidades y escribir `INTERRUPTED` con origen `system`. `AUDIO_DEAD_OBJECT`
o un error de lectura durante la preempción siguen la misma política recuperable;
errores de formato, alineación o integridad permanecen terminales.

La llamada es un caso separado porque el sistema puede reservar el micrófono para
telefonía. G1 exige que el comportamiento sea explícito y recuperable, no que se
grabe el audio de la llamada.

## Recolección y criterios

Para cada sesión finalizada:

```powershell
.\tools\collect-device-session.ps1 `
  -Adb $adb `
  -Serial R5CY20HYBGJ `
  -SessionId <sessionId> `
  -RequireLifecycleEvent STARTED,PAUSED,RESUMED,COMPLETED
```

La evidencia privada debe contener `verification.json` válido. El informe
sanitizado de G1 debe registrar:

- build, dispositivo y configuración;
- duración útil y tiempo con pantalla apagada;
- bytes esperados frente a bytes reales;
- número de segmentos y validación SHA-256;
- errores de lectura, discontinuidades y frames estimados perdidos;
- comportamiento de notificación, pausa, background, cierre forzado y llamada;
- diario técnico ordenado con origen `ui`, `notification`, `system` o `runtime`;
- riesgos observados y decisión explícita `CONTINUAR`, `AJUSTAR` o `DETENER`.

Para el caso A, combinar la verificación con el monitor y las acciones temporizadas:

```powershell
python .\tools\evaluate_g1_capture.py `
  --verification <device-session>\verification.json `
  --monitor-jsonl <monitor>\samples.jsonl `
  --timed-actions-jsonl <monitor>\timed-actions.jsonl `
  --output <monitor>\case-a-evaluation.json `
  --fail-on-automatic-check
```

El evaluador exige 90 minutos útiles, 80 minutos observados con pantalla apagada,
salud continua del proceso/servicio, pausa de 10 segundos entre los minutos 30–35,
un minuto en segundo plano entre los minutos 60–65 y ausencia de errores del arnés.

G1 solo se aprueba si el caso A cumple 90 minutos sin pérdida/corrupción, el caso B
recupera sin sobrescritura y los tres casos dejan un resultado reproducible. Un
fallo en llamada puede ser aceptable únicamente si se informa y permite recuperar
la sesión sin corrupción; un hueco no contabilizado bloquea la puerta.
