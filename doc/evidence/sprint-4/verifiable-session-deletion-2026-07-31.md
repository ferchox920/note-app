# Evidencia: borrado completo y verificable de una sesión

## Resultado

**APROBADO (2026-07-31).** El borrado de sesiones terminales es explícito,
recuperable ante interrupciones e idempotente. El flujo elimina los artefactos
de la sesión y su grafo relacional de Room, y comprueba ambas ausencias antes de
declarar éxito.

La validación física utilizó exclusivamente datos sintéticos dentro del paquete
de pruebas. **No se eliminó ninguna sesión real del usuario.**

## Experiencia y límites de seguridad

- Sólo se aceptan sesiones `COMPLETED`, `FAILED` o `ABORTED`; una sesión activa
  se rechaza sin modificar archivos ni base de datos.
- La acción muestra una confirmación que enumera audio, transcripción, notas,
  trabajos y métricas, con opciones separadas para cancelar o **Eliminar
  definitivamente**.
- El botón queda deshabilitado mientras se procesa ASR o ya hay un borrado en
  curso, evitando operaciones simultáneas sobre la misma sesión.
- Los identificadores y rutas se validan como hijos directos del directorio de
  grabaciones. La eliminación recursiva no sigue enlaces simbólicos.

## Protocolo de borrado y recuperación

1. El directorio autoritativo `<sessionId>` se renombra atómicamente a
   `.deleting-<sessionId>` en el mismo sistema de archivos.
2. La fila `Session` se elimina dentro de una transacción Room. Las claves
   foráneas con `ON DELETE CASCADE` eliminan segmentos de transcripción, notas,
   trabajos de procesamiento y métricas.
3. Se elimina recursivamente el tombstone, sin seguir enlaces.
4. Se verifica que no existan el directorio original, el tombstone ni ninguna
   de las relaciones de base de datos de la sesión.

Si el proceso muere entre esos pasos, el tombstone determinista conserva el
estado pendiente. `recoverInterrupted()` completa primero las cascadas, elimina
los bytes y vuelve a verificar. Repetir la recuperación o el borrado produce el
mismo estado final. Un estado ambiguo —origen y tombstone simultáneos— falla
cerrado para no elegir silenciosamente qué copia destruir.

## Pruebas reproducibles

La suite instrumentada valida en almacenamiento aislado:

- eliminación del directorio, sus archivos anidados y todas las cascadas;
- repetición idempotente de un borrado ya completado;
- recuperación idempotente desde un tombstone que simula una interrupción;
- rechazo de una sesión activa conservando sus archivos y su fila;
- conservación de relaciones al actualizar normalmente una sesión.

Comando ejecutado con el S25 Ultra conectado:

```powershell
.\gradlew.bat `
  :core-storage:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.noteapp.storage.NoteAppDatabaseTest `
  --no-daemon --max-workers=1
```

Resultado: **11/11 pruebas aprobadas** en `SM-S938B` con Android 16. La suite se
ejecutó dos veces para comprobar repetibilidad. Además aprobaron
`:core-storage:assembleDebugAndroidTest`,
`:feature-recording:testDebugUnitTest`, `:app:assembleDebug`, `:app:lintDebug` y
44/44 pruebas de herramientas. El commit de implementación `3382a1b` pasó
[Android CI](https://github.com/ferchox920/note-app/actions/runs/30678709330).

## Comprobación de no regresión sobre datos reales

Después de instalar la compilación mediante actualización que preserva datos,
la auditoría cifrada autenticó dos veces:

- 14 sesiones reales;
- 114/114 artefactos cifrados;
- 24 segmentos PCM;
- 210.056.163 bytes almacenados antes y después;
- cero archivos planos, desconocidos o temporales.

La auditoría declara `recordingStartedByAudit=false`,
`transcriptionStartedByAudit=false` y `appDataCleared=false`. Por tanto, la
validación no grabó, no transcribió, no borró datos reales y no limpió el
almacenamiento de la aplicación. El informe detallado permanece en el área
privada ignorada por Git porque contiene metadatos del dispositivo, aunque no
incluye audio ni texto.

## Alcance de la garantía

El borrado garantiza la ausencia verificable de archivos y filas accesibles a
la aplicación. No promete sobreescritura forense de bloques físicos de memoria
flash, una propiedad que Android y el almacenamiento con wear levelling no
exponen a la app. Los artefactos ya estaban cifrados con AES-256-GCM y clave no
exportable de Android Keystore antes de su eliminación.

## Decisión

Se cierra el P0 de borrado completo y verificable de una sesión. Esta decisión
no aprueba G3 por sí sola: continúan pendientes la validación con reinicio real,
BiometricPrompt opcional y la retención configurable con advertencia de
consentimiento.
