# Proto DataStore para preferencias de transcripción — 2026-07-29

## Objetivo

Completar el segundo P0 del Sprint 4 según `DocMaster.md`, que prescribe Proto
DataStore para datos pequeños no relacionales. El alcance se limitó a
preferencias reales del flujo de transcripción; Room sigue siendo la autoridad
para sesiones, segmentos, notas, trabajos y métricas.

Se utilizaron AndroidX DataStore 1.2.1, Protocol Buffers 4.32.1 y el plugin
Protobuf 0.9.5. Referencias oficiales:

- <https://developer.android.com/topic/libraries/architecture/datastore>
- <https://developer.android.com/jetpack/androidx/releases/datastore>

## Implementación

- Esquema `StoredAppPreferences` con pipeline de captura, habilitación/modelo
  ASR incremental, cantidad de hilos y duración de chunk para benchmark.
- Una sola instancia de DataStore para `app_preferences.pb`, creada por Hilt
  con scope de aplicación.
- API de dominio `AppPreferencesStore` basada en `Flow` y actualizaciones
  atómicas.
- Valores conservadores cuando todavía no existe un archivo:
  `direct-16k`, ASR incremental desactivado, 4 hilos y chunks de 30 segundos.
- Validación antes de cada escritura:
  pipelines conocidos, hilos `2/4/6/8`, chunks `10/20/30` e identificadores de
  modelo acotados.
- Valores persistidos desconocidos degradan individualmente al valor por
  defecto, sin invalidar las demás preferencias.
- Un protobuf malformado produce `CorruptionException`; el DataStore de
  producción lo reemplaza por el mensaje por defecto.
- `RecordingViewModel` consume las preferencias tipadas y las aplica a los
  replays Whisper/Sherpa y a la próxima sesión incremental.
- La pantalla deja de mantener copias efímeras de modelo, hilos y chunk; sus
  controles se habilitan después de la primera lectura persistida.

No se guardan en DataStore audio, texto de transcripción, contenido de notas,
identificadores de sesión ni telemetría.

## Validación

Dispositivo físico:

- Samsung Galaxy S25 Ultra `SM-S938B`, Android API 36, serial
  `R5CY20HYBGJ`.
- `:core-storage:connectedDebugAndroidTest`: 8/8 pruebas aprobadas. La prueba
  nueva ejecutó cuatro escrituras reales y confirmó
  `datastore/app_preferences.pb`; las otras siete cubrieron Room.
- APK debug instalada correctamente.
- En la UI se cambiaron únicamente parámetros de benchmark de 4/30 a 6 hilos y
  20 segundos.
- Después de `am force-stop com.noteapp` y un nuevo arranque, la UI mostró
  `Benchmark: 6 hilos · chunks 20s`, `[6] h` y `[20] s`.
- `run-as com.noteapp ls -l files/datastore` confirmó el archivo
  `app_preferences.pb`.
- No se inició grabación, llamada ni retranscripción.

Pruebas automatizadas:

- Pruebas JVM del repositorio: valores iniciales, actualizaciones tipadas,
  desactivación de ASR incremental, rechazo sin mutación, degradación de datos
  desconocidos y detección de protobuf corrupto.
- `gradlew build lint test --no-daemon --max-workers=1 --stacktrace`: correcto,
  912 tareas accionables.
- `python -m unittest discover -s tools/tests -p 'test_*.py' -v`: 36/36
  pruebas aprobadas.

## Estado y límites

El P0 de DataStore queda completo para las preferencias no relacionales que
existen hoy. No se añadieron flags especulativos de biometría o retención porque
todavía no tienen comportamiento asociado.

Esta evidencia no aprueba G3 ni afirma cifrado en reposo. Continúan pendientes
SQLCipher/Keystore, cifrado de audio y artefactos sensibles, recuperación
integral ante fallos, borrado verificable y BiometricPrompt.
