# SQLCipher y Android Keystore — 2026-07-29

## Objetivo

Completar el P0 de Sprint 4 que protege la base Room en reposo, conservando las
sesiones ya existentes. El diseño sigue `DocMaster.md`: SQLCipher para la base
completa y Android Keystore para que la clave de envoltura no sea exportable.

Se utilizó `net.zetetic:sqlcipher-android:4.17.0`, la biblioteca Android vigente
que reemplaza al artefacto legado `android-database-sqlcipher`. Referencias
oficiales:

- <https://github.com/sqlcipher/sqlcipher-android/releases>
- <https://www.zetetic.net/sqlcipher/encrypting-plaintext-databases/>
- <https://www.zetetic.net/sqlcipher/sqlcipher-api/>
- <https://developer.android.com/privacy-and-security/keystore>
- <https://developer.android.com/privacy-and-security/cryptography>

## Gestión de claves

- Módulo independiente `core-security`.
- Entropía aleatoria de 256 bits para cada instalación, representada como 64
  bytes hexadecimales para SQLCipher.
- Clave de envoltura AES-256 creada dentro de `AndroidKeyStore`, con modo GCM,
  padding desactivado y cifrado aleatorio obligatorio.
- El passphrase se guarda únicamente envuelto con AES-256-GCM, IV aleatorio,
  etiqueta de autenticación de 128 bits y AAD versionado.
- Formato binario versionado, tamaño acotado y rechazo de datos truncados,
  desconocidos o con contenido adicional.
- Escritura del material envuelto mediante temporal, `fsync`, movimiento
  atómico y permisos exclusivos del propietario.
- Los buffers temporales de entropía y passphrase se sobrescriben después de
  usarse.
- Si existe una base cifrada pero falta el archivo envuelto o la clave de
  Keystore, la app falla de forma cerrada y no crea una clave sustituta.

## Migración de SQLite plano

Antes de abrir Room se detecta la cabecera `SQLite format 3`. Para una base
existente:

1. Se ejecuta un checkpoint completo del WAL y se cambia el journal a `DELETE`.
2. Se crea una base temporal cifrada y se adjunta la base plana con clave vacía.
3. `sqlcipher_export` copia el esquema y los datos hacia la base cifrada.
4. Se conserva `user_version` y se ejecuta `integrity_check`.
5. Se restringen permisos y se verifica una segunda apertura con la clave.
6. La base plana pasa a respaldo y la cifrada ocupa el nombre definitivo
   mediante movimientos atómicos.
7. Tras otra verificación se elimina el respaldo plano y cualquier sidecar.

El arranque recupera de forma determinista los estados en que quedaron un
respaldo o un temporal por una interrupción. Un estado ambiguo o una base que no
abre con la clave esperada se rechaza sin regenerar secretos.

## Validación automatizada

Dispositivo físico Samsung Galaxy S25 Ultra `SM-S938B`, Android API 36, serial
`R5CY20HYBGJ`:

- `:core-security:connectedDebugAndroidTest`: 1/1. Confirmó passphrase estable,
  contenido no legible en el archivo envuelto y rechazo de una etiqueta GCM
  alterada.
- `:core-storage:connectedDebugAndroidTest`: 11/11. Incluyó migración de Room
  sin pérdida de filas, recuperación desde respaldo interrumpido, rechazo de
  clave incorrecta o ausente y rechazo de la base cifrada por SQLite estándar.
- `:core-security:testDebugUnitTest`: correcto; cubre ida y vuelta del formato
  envuelto y rechazo de datos malformados o adicionales.
- `gradlew build lint test --no-daemon --max-workers=1 --stacktrace`: correcto.
- `python -m unittest discover -s tools/tests -p 'test_*.py' -v`: 36/36
  pruebas aprobadas.

## Migración real en el S25 Ultra

La APK se instaló encima de la versión funcional, sin limpiar datos:

- Antes: `note-app.db` tenía 106496 bytes, WAL de 444992 bytes y cabecera Base64
  `U1FMaXRlIGZvcm1hdCAzAA==` (`SQLite format 3`).
- Después: la cabecera Base64 fue `ciOiAklocDiFIULnTzHjOw==`, distinta y no
  identificable como SQLite plano.
- `files/security/database-passphrase.v1` quedó con 112 bytes y permisos
  `-rw-------`; su directorio quedó `drwx------`.
- La base y sus sidecars quedaron con permisos `-rw-------`.
- No quedaron archivos `.plaintext.backup` ni `.encrypted.tmp`.
- La UI conservó la sesión completada
  `d24dee16-cff9-42ed-8b0c-ff0dd6ddbd63`, su duración de 24 segundos y el estado
  de transcripción incremental.
- Tras `am force-stop` y un segundo arranque, la misma sesión y el estado de
  transcripción siguieron visibles.
- No hubo errores `AndroidRuntime` ni se expusieron claves, audio o texto en
  logs.
- No se inició ninguna grabación, llamada ni retranscripción.

## Estado y límites

Este P0 protege las tablas de Room y completa SQLCipher/Keystore. No aprueba G3:
los PCM, checkpoints, resultados ASR y otros artefactos bajo `files/recordings`
todavía son archivos planos. El siguiente P0 debe cifrar esos archivos en
reposo y definir una migración compatible. El borrado físico seguro sobre
almacenamiento flash tampoco puede garantizarse mediante una eliminación de
archivo común; el borrado verificable integral se mantiene como un P0 separado.
