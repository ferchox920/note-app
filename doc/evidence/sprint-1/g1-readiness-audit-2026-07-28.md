# Auditoría de preparación G1 — 2026-07-28

## Alcance y decisión

Esta auditoría contrasta Sprint 1 de `PLAN_DE_SPRINTS.md` con el código, las pruebas
y el estado real del Galaxy S25 Ultra. No reemplaza la matriz física de
[`g1-device-gate-protocol.md`](g1-device-gate-protocol.md).

**Decisión actual: G1 NO EVALUADA.** La implementación y la instrumentación están
listas, pero todavía faltan la grabación válida de 90 minutos, el cierre forzado
recuperable y la interrupción por llamada. No se debe avanzar a la puerta G2 hasta
obtener esas evidencias.

## Build y dispositivo preparados

- Dispositivo objetivo conectado y autorizado: Galaxy S25 Ultra `SM-S938B`.
- Android 16 / API 36.
- Variante instalada: `0.1.0-spike-benchmark`, mediante `adb install -r`.
- APK preparada: 136.555.856 bytes.
- SHA-256 de la APK endurecida con confirmación de cierre:
  `8d24ca63f3a5c0653c86931e964412a6b1cebeb7e94d360874e881e524d252de`.
- Datos, modelos y sesiones previas preservados.
- Ningún servicio de grabación activo al cerrar esta auditoría.

## Backlog de Sprint 1

| Requisito | Estado | Evidencia |
|---|---|---|
| Módulos iniciales | Implementado | `settings.gradle.kts` incluye `app`, `core-domain`, `core-audio`, `core-storage`, `feature-recording`, `benchmark` y `shared-testing` |
| Navegación, Hilt, UDF/ViewModel y permisos | Implementado | `AppNavigation`, `@HiltAndroidApp`, `@AndroidEntryPoint`, `RecordingViewModel` y solicitud de micrófono/notificaciones |
| Foreground service visible | Implementado; falta stress físico | `AudioCaptureService`, tipo `microphone`, notificación persistente observada en prueba corta |
| Estados de sesión | Implementado y probado | `NEW`, `RECORDING`, `PAUSED`, `RECOVERING`, `COMPLETED`, `FAILED`, `ABORTED` |
| Pausa/reanudación y segmentos con checksum | Implementado y probado en JVM | cierre de segmento, offsets contiguos y SHA-256; falta transición física larga |
| Checkpoint de sesión incompleta | Implementado y probado en JVM | reemplazo atómico cada 10 s, detección recuperable y adopción de segmento huérfano |
| Pantalla apagada, background y llamada | Pendiente | requiere los casos A y C del protocolo físico |
| Duración, bytes y continuidad | Implementado | checkpoint y monitor registran duración, bytes, errores, discontinuidades y frames estimados |
| CI reproducible | Configurado; ejecución actual pendiente | `.github/workflows/android.yml` ejecuta build/lint/test y pruebas Python |

## Instrumentación agregada para la ejecución

Cada sesión nueva mantiene eventos atómicos ordenados en
`lifecycle-events/event-NNNN.json`. Un evento contiene únicamente secuencia, estado,
origen del comando, tiempos técnicos, duración PCM, bytes y código de error. No
incluye audio ni texto.

El verificador comprueba:

- secuencia y timestamps monotónicos;
- duración y bytes dentro del checkpoint final;
- identidad de sesión y estados válidos;
- presencia exigible de `STARTED`, `PAUSED`, `RESUMED` y `COMPLETED`;
- origen normal de producto (`ui`, `notification`, `system`, `runtime` o `unknown`)
  y origen explícito del arnés (`adb-harness`) durante una prueba física controlada.

Esto permite distinguir un cierre solicitado desde UI de uno enviado desde la
notificación o provocado por ciclo de vida, sin depender de memoria humana.

## Verificación ejecutada

- `:core-audio:testDebugUnitTest`: 11 pruebas aprobadas.
- `:app:assembleBenchmark`: aprobado.
- `:app:lintBenchmark`: aprobado.
- `gradlew build lint test` sobre el árbol actual: aprobado.
- `python -m unittest discover -s tools/tests`: 20 pruebas aprobadas.
- `git diff --check`: aprobado.
- Instalación física con preservación de datos: aprobada.

## Evidencia que falta para cerrar G1

1. Caso A: al menos 5.400.000 ms de audio útil, pantalla apagada durante al menos
   80 minutos, pausa/reanudación y un minuto en background.
2. Caso B: cierre forzado, checkpoint `RECOVERING`, reanudación y cierre
   `COMPLETED` sin sobrescribir segmentos.
3. Caso C: llamada real, comportamiento explícito y recuperación sin corrupción ni
   huecos silenciosos.
4. Recolección privada y `verification.json` válido para cada sesión.
5. Informe sanitizado final con decisión `CONTINUAR`, `AJUSTAR` o `DETENER`.

El desbloqueo físico del S25 Ultra ya fue resuelto. El caso A se inició después de
esta auditoría; G1 continuará como no evaluada hasta completar y recolectar los tres
casos.

## Hallazgo del arnés físico

Dos intentos cortos terminaron en `COMPLETED` al inyectar `KEYCODE_POWER` desde ADB.
El diario demostró `source=ui`: el evento sintético activó el botón Compose enfocado;
no fue un crash ni una orden de la notificación. Esos intentos no cuentan para G1.

La app ahora exige confirmar el cierre desde UI y el protocolo deja apagar la
pantalla por timeout natural. Esto separa el comportamiento real del usuario de un
artefacto del arnés ADB.
