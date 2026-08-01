# Evidencia física: artefactos de sesión cifrados

## Resultado

**APROBADO en S25 Ultra (2026-07-31).** La APK debug basada en `bf19432`, con
la corrección de compatibilidad `656b937`, migró y autenticó las sesiones reales
sin borrar datos, grabar audio ni iniciar ASR.

El primer intento físico reveló que Android Keystore del dispositivo rechazaba
un IV suministrado por la aplicación (`Caller-provided IV not permitted`). La
corrección mantiene `randomizedEncryptionRequired=true` y obtiene un IV nuevo
del propio proveedor Keystore para cada frame AES-256-GCM. También se corrigió
el lector de cabeceras del arnés, cuyo pipeline remoto producía un falso timeout.

## Entorno y alcance

- Dispositivo: Samsung SM-S938B (`pa3q`), Android 16 / API 36.
- Instalación: actualización con preservación de datos (`adb install -r -t`).
- Datos de la app borrados: no.
- Grabación iniciada por la auditoría: no.
- Transcripción iniciada por la auditoría: no.
- Contenido privado incluido en la evidencia: no.

## Resultados sanitizados

| Comprobación | Resultado |
|---|---:|
| Sesiones autenticadas | 14 |
| Artefactos autenticados | 114 |
| Segmentos PCM autenticados | 24 |
| Bytes planos verificados dentro de la app | 207.527.040 |
| Bytes almacenados cifrados | 210.056.163 |
| Artefactos planos o desconocidos | 0 |
| Temporales residuales | 0 |
| Segunda apertura y auditoría | Aprobada |

Los 114 artefactos conservaron el mismo total de bytes entre aperturas. La
auditoría verificó autenticación, binding de ruta, offsets/checksums PCM,
permisos privados y ausencia de temporales. La segunda ejecución volvió a
autenticar las 14 sesiones y los 24 segmentos, demostrando idempotencia.

El reporte privado sanitizado quedó en
`artifacts/private/sprint-4/encrypted-artifacts-20260731-214406.json` y está
excluido de Git.

## Relación con `SESSION_NOT_INDEXED`

El cambio `e5b3c48` añadió un refresco idempotente del índice Room antes de crear
un trabajo ASR. Tras corregir la inicialización Keystore, la app volvió a abrir
normalmente sobre las 14 sesiones cifradas y ejecutó ese refresco sin iniciar
una transcripción.

Una prueba de uso posterior volvió a mostrar `SESSION_NOT_INDEXED`. El refresco
estaba en la pantalla, pero no era una garantía del límite que persiste el
trabajo. La corrección posterior trasladó la autorreparación a
`RoomProcessingTelemetryStore.start`: si la sesión falta, reconstruye el índice,
vuelve a comprobarla y solo entonces crea el trabajo. La regresión se validó en
el S25 Ultra con una base aislada, sin abrir el micrófono ni ejecutar ASR.

## Regresión

- Auditoría instrumentada física: aprobada dos veces.
- Build, lint y pruebas JVM de todos los módulos: aprobados, 1036 tareas.
- Pruebas de herramientas: 37/37 aprobadas.
- Servicio de grabación tras la auditoría: inactivo.
- Paquete auxiliar de pruebas: desinstalado.

## Decisión

Se cierra el P0 de cifrado de audio y artefactos sensibles en reposo. G3 aún no
se declara superada: continúan separados los P0 de recuperación integral,
BiometricPrompt y borrado verificable.
