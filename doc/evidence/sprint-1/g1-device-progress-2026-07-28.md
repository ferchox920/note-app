# Avance físico G1 — 2026-07-28

## Decisión provisional

**G1 = AJUSTAR.** La captura larga obtenida es íntegra y el caso de recuperación
ya está aprobado, pero la ejecución se cerró manualmente a los 72,59 minutos.
Por lo tanto, no demuestra todavía los umbrales estrictos de 90 minutos útiles ni
80 minutos observados con la pantalla apagada. El caso de llamada real también
permanece pendiente.

La evidencia privada contiene audio y está ignorada por Git. Este informe solo
incluye métricas técnicas, identificadores de prueba y resultados sanitizados.

## Caso A — captura larga parcial

- Dispositivo: Galaxy S25 Ultra `SM-S938B`, Android 16 / API 36.
- Sesión: `da7cef02-eac8-4338-a666-437e784f6b44`.
- Estado final: `COMPLETED`, cierre confirmado desde UI.
- Duración útil: 4.355.160 ms (72,59 min).
- Pantalla apagada observada: 2.960.831 ms (49,35 min).
- PCM: 139.365.120 bytes distribuidos en 3 segmentos.
- Integridad: los tres archivos coinciden con sus longitudes y SHA-256
  registrados; no quedaron PCM sin listar.
- Lectura: 0 errores, 0 discontinuidades y 0 frames estimados perdidos.
- VAD WebRTC: 1.004 segmentos y 4.355.160 ms procesados, igual a la duración PCM.
- Ciclo de vida: `STARTED`, pausas/reanudaciones auditadas y `COMPLETED`; la
  transición planificada de 10 s ocurrió dentro de los minutos 30–35.
- Background: la sesión permaneció `RECORDING` más de 60 s entre los minutos
  60–65.
- Notificación pausada: reflejó `Grabación pausada` y presentó las acciones
  `Reanudar` y `Finalizar`.

El evaluador automático aprobó integridad, cobertura VAD, ciclo de vida, salud del
monitor, pausa/reanudación y permanencia en background. Falló correctamente:

1. duración inferior a 90 minutos;
2. pantalla apagada inferior a 80 minutos;
3. notificación de grabación ausente en el registro del sistema durante el
   intervalo en background;
4. hito de 90 minutos no alcanzado.

Esta ejecución sirve como evidencia de estabilidad continua por 72,59 minutos,
pero no se etiqueta como caso A aprobado.

## Corrección y regresión de notificación

El defecto encontrado al reanudar se corrigió en `cc6ceca`: la primera publicación
entra mediante `startForeground`, las actualizaciones posteriores usan
`NotificationManager.notify` y Android 12+ solicita comportamiento foreground
inmediato.

- APK benchmark: 136.567.172 bytes.
- SHA-256:
  `56663e04c4ddab7da8836c295ef779a0dd4f1d83df2086e0f23222ea71c32ea0`.
- Instalación: `adb install -r`, datos y modelos preservados.
- Sesión de regresión: `66cc0ad9-e48a-4343-a085-7ddff31abf97`.
- Resultado: aprobado en grabación visible, app en background, pausa y
  reanudación.
- Estado grabando: `Grabación en curso`, acciones `Pausar` y `Finalizar`.
- Estado pausado: `Grabación pausada`, acciones `Reanudar` y `Finalizar`.
- En todos los snapshots el servicio permaneció foreground y la notificación
  estuvo presente.

El arnés reproducible está en `tools/run-g1-notification-case.ps1`.

## Caso B — cierre forzado y recuperación

**Resultado: APROBADO** sobre la misma APK corregida.

- Sesión: `66cc0ad9-e48a-4343-a085-7ddff31abf97`.
- Duración antes del cierre: 289.140 ms.
- Duración al recuperar: 289.140 ms; no volvió a cero.
- Duración después de recuperación: 354.840 ms.
- Duración final: 366.960 ms.
- Estado final: `COMPLETED`.
- Segmentos protegidos tras el cierre: 5.
- Segmentos finales: 7.
- Todos los segmentos previos conservaron longitud y SHA-256.
- PCM final: 11.742.720 bytes, sin archivos no listados.
- Lectura final: 0 errores, 0 discontinuidades y 0 frames estimados perdidos.
- Diario verificado: contiene `RECOVERY_STARTED`, `RECOVERED`, pausa,
  reanudación y cierre.

## Verificación de software

- `gradlew build lint test`: aprobado.
- `python -m unittest discover -s tools/tests`: 27 pruebas aprobadas.
- Instalación física de la APK corregida: aprobada.

## Caso C — intento sin estímulo de llamada

Se preparó una sesión nueva y el observador publicó `CALL_WINDOW_READY` después
de superar 60 segundos de audio previo. Durante los 300 segundos configurados no
se observó ninguna transición de telefonía: las dos suscripciones permanecieron
en estado `IDLE`.

- Sesión: `de478705-b654-44db-b819-2b0ce950d79f`.
- Duración final: 379.720 ms.
- PCM: 12.151.040 bytes.
- Lectura: 0 errores, 0 discontinuidades y 0 frames estimados perdidos.
- Cierre: controlado mediante `adb-harness`.

La sesión se recolectó y verificó para demostrar que el timeout del arnés no deja
el micrófono activo. **No cuenta como caso C**, porque no existió una llamada real.

## Matriz automática con la evidencia actual

Se ejecutó `tools/evaluate_g1_matrix.py` aun sabiendo que A y C estaban
incompletos, para obtener un diagnóstico reproducible:

- `caseALongCapturePassed`: `false`;
- `caseBRecoveryPassed`: `true`;
- `caseCCallInterruptionPassed`: `false`;
- estado global: `AUTOMATIC_CHECKS_FAILED`;
- `approved`: `false`.

Los seis checks internos del caso B pasaron. En C solo se confirmó el modelo S25
Ultra; fallaron correctamente llamada atendida real, resultado de la interrupción,
audio posterior y cierre con el ciclo de vida exigido.

## Trabajo restante antes de cerrar G1

1. Repetir el caso C cuando haya otro teléfono disponible, atender la llamada
   durante 15–30 segundos y confirmar recuperación explícita sin corrupción.
2. Repetir el caso A hasta 90 minutos útiles y 80 minutos de pantalla apagada si
   se desea superar estrictamente la puerta G1.
3. Ejecutar la matriz conjunta y emitir la decisión manual final.

No se debe declarar G1 aprobada ni avanzar formalmente a G2 con los datos
actuales. Sí puede continuarse con el caso C y con correcciones de Sprint 1.
