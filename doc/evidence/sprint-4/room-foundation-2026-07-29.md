# Base Room e indexación de artefactos — 2026-07-29

## Objetivo

Iniciar el primer P0 del Sprint 4 sin grabar una sesión nueva y sin reemplazar
prematuramente el mecanismo de recuperación que ya funciona. La decisión sigue
el modelo de datos de `DocMaster.md`: Room concentra los datos estructurados,
mientras los checkpoints y artefactos atómicos continúan siendo la autoridad
durante esta transición.

Se adoptó Room 2.8.4, la versión estable de la línea AndroidX Room 2 al momento
de la implementación. Referencia oficial:
<https://developer.android.com/jetpack/androidx/releases/room>.

## Implementación

- `NoteAppDatabase`, esquema v1 exportado y versionado.
- Tablas `sessions`, `transcript_segments`, `notes`, `processing_jobs` y
  `session_metrics`.
- Claves foráneas hacia `sessions` con borrado en cascada.
- Índices por sesión y por los campos de consulta iniciales de trabajos y
  métricas.
- DAOs base para las cinco entidades.
- `RoomSessionCheckpointStore` conectado mediante Hilt al flujo usado por
  `RecordingViewModel`.
- Lectura robusta con `JSONObject` de `checkpoint.json` y
  `incremental-transcript.json`; esto reemplaza el parser anterior basado en
  expresiones regulares.
- Reindexación idempotente dentro de una transacción: actualiza la sesión,
  reemplaza únicamente sus segmentos finales y conserva los archivos originales.
- `@Upsert` para sesiones, evitando la semántica `REPLACE` de SQLite que podría
  disparar un borrado en cascada de registros relacionados.

Los segmentos guardan secuencia, timestamps, texto estable/final y modelo de
origen. El índice no importa todavía el journal completo de telemetría: hacerlo
en cada arranque aumentaría innecesariamente E/S y memoria en sesiones largas.

## Validación

Dispositivo físico:

- Samsung Galaxy S25 Ultra `SM-S938B`, Android API 36.
- `:core-storage:connectedDebugAndroidTest`: 3/3 pruebas aprobadas.
- Cobertura: orden y reemplazo de segmentos, actualización de una sesión sin
  perder relaciones, borrado en cascada y reindexación idempotente desde
  artefactos.
- APK debug instalada y abierta correctamente.
- La app creó `note-app.db`, sus archivos WAL/SHM y mantuvo visibles los
  directorios de las sesiones existentes.
- No se inició ninguna grabación ni retranscripción para esta validación.

Regresión:

- `gradlew build lint test --stacktrace`: correcto, 898 tareas accionables.
- `python -m unittest discover -s tools/tests -v`: 36/36 pruebas aprobadas.

## Estado y límites

Esta evidencia no aprueba G3 ni afirma cifrado en reposo. La historia Room sigue
abierta hasta conectar la escritura funcional de notas, trabajos y métricas.
También permanecen pendientes DataStore, SQLCipher/Keystore, cifrado de
artefactos, borrado verificable completo y las pruebas reproducibles de cierre
forzado/reinicio exigidas por Sprint 4.
