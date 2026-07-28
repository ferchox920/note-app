# Ejecución de puertas físicas G0–G2

Estado actual: **G0 superada técnicamente; avanzar a G1**. La sesión autorizada de
6:18 en el Galaxy S25 Ultra `SM-S938B` (`R5CY20HYBGJ`) conservó captura/VAD
íntegros. La revalidación optimizada del 28 de julio obtuvo RTF 0,153/0,191 para
tiny/base. G1 y G2 mantienen sus propias pruebas de 90/45 minutos.

Estas instrucciones producen evidencia privada verificable. No convierten una prueba
automatizada en aprobación: calidad de texto, duplicaciones, pantalla apagada y
condiciones ambientales requieren revisión humana.

## Preparación única

```powershell
adb devices -l
.\tools\download-whisper-models.ps1 -Model all
.\tools\prepare-asr-lab.ps1
```

Debe aparecer exactamente un S25 autorizado. Importar desde la UI los modelos que se
usarán. La app muestra un ID de sesión seleccionable para los comandos posteriores.

## G0 — viabilidad Audio/VAD/Whisper

Ejecutar la sesión corta definida en los protocolos de captura y ASR, transcribir el
mismo audio con tiny y base y luego:

```powershell
.\tools\collect-device-session.ps1 `
  -SessionId <sessionId> `
  -RequireAsrModel @(
    'whisper-tiny-multilingual-q5_1',
    'whisper-base-multilingual-q5_1'
  )
```

Revisar `verification.json` y ambos JSON ASR. G0 requiere integración correcta y al
menos una configuración con RTF cercano o inferior a 1.

## G1 — captura fiable

Grabar 90 minutos con pantalla apagada, incluyendo pausa/reanudación, y finalizar:

```powershell
.\tools\collect-device-session.ps1 `
  -SessionId <sessionId> `
  -RequireLifecycleEvent STARTED,PAUSED,RESUMED,COMPLETED
```

`verification.json` debe mostrar estado `COMPLETED`, cero errores/discontinuidades
sin explicar, PCM contiguo y checksums válidos. Inspeccionar también notificación,
duración real y comportamiento durante llamadas/interrupciones.

Para capturar el estado inmediatamente posterior a un cierre forzado, antes de tocar
`Reanudar`:

```powershell
.\tools\collect-device-session.ps1 `
  -SessionId <sessionId> `
  -ExpectedStatus RECOVERING `
  -AllowUnlistedPcm
```

Después completar la recuperación y volver a extraerla con el comando normal.

## G2 — ASR incremental útil

Tras aprobar G0/G1, seleccionar el modelo candidato, grabar 45 minutos autorizados y
extraer:

```powershell
.\tools\collect-device-session.ps1 `
  -SessionId <sessionId> `
  -RequireIncremental

python .\tools\evaluate_incremental.py `
  --results-dir .\artifacts\private\device-sessions `
  --output-dir .\artifacts\private\incremental-evaluation `
  --consent-confirmed
```

G2 requiere primer parcial ≤ 4 s, latencia visible ≤ 6 s, RTF sostenido ≤ 1,
captura estable y ausencia de duplicaciones graves. El evaluador solo decide si la
sesión es elegible para revisión manual.

## Matriz sanitizada de decisión

Cuando existan tres extracciones separadas para G0, G1 y G2, consolidar los umbrales:

```powershell
python .\tools\evaluate_device_gates.py `
  --g0-evidence .\artifacts\private\device-sessions\<g0> `
  --g1-evidence .\artifacts\private\device-sessions\<g1> `
  --g2-evidence .\artifacts\private\device-sessions\<g2> `
  --output .\artifacts\private\device-gates.json `
  --fail-on-automatic-check
```

El reporte comprueba que sea un S25 Ultra, duración, integridad de captura,
tiny/base, RTF, telemetría y umbrales incrementales. Su campo `approved` siempre es
`false`: un resultado `ELIGIBLE_FOR_MANUAL_REVIEW` exige confirmar consentimiento,
pantalla apagada, calidad, duplicaciones, interrupciones y comportamiento térmico.

## Privacidad

Cada extracción crea `artifacts/private/device-sessions/<sesión>-<fecha>/` con:

- `session.tar` y `session/`: PCM y transcripciones sensibles;
- `device.json`: configuración técnica sin serial ADB;
- `verification.json`: resumen sanitizado sin contenido hablado.

Todo `artifacts/private/` está ignorado por Git. No compartir ni versionar los dos
primeros artefactos sin consentimiento explícito.
