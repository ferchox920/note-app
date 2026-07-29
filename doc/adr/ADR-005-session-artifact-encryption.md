# ADR-005: Cifrado autenticado de artefactos de sesión

- Estado: aceptado
- Fecha: 2026-07-29

## Contexto

SQLCipher protege las tablas de Room, pero cada sesión también produce PCM,
checkpoints, eventos de ciclo de vida, timelines VAD, transcripciones
incrementales, journals de métricas y resultados ASR. Estos archivos contienen
audio o texto sensible y antes quedaban legibles bajo `files/recordings`.

El PCM debe escribirse mientras la captura está activa y puede superar cientos
de megabytes. Un único mensaje AES-GCM requeriría conservar todo el archivo
abierto como una sola operación y no ofrecería puntos de recuperación
autenticados. Las APIs `EncryptedFile` de `androidx.security:security-crypto`
están deprecadas y tampoco resuelven el append de audio.

## Decisión

- Crear una clave AES de 256 bits no exportable en Android Keystore con alias
  versionado `noteapp.session-artifacts.v1`.
- Mantener un marcador no secreto en `files/security` para detectar pérdida de
  la clave y fallar de forma cerrada, sin regenerarla sobre datos cifrados.
- Usar un contenedor propio versionado basado exclusivamente en primitivas del
  sistema:
  - cabecera con magic, versión y SHA-256 de la ruta lógica relativa;
  - frames independientes AES-256-GCM;
  - IV aleatorio de 96 bits por frame;
  - etiqueta de autenticación de 128 bits;
  - AAD con cabecera, secuencia y longitud.
- Ligar cada archivo a `sesión/ruta` para que copiar ciphertext entre sesiones
  no produzca contenido válido.
- Escribir PCM y journals como secuencias de frames autenticados. Los JSON se
  cifran primero en un temporal, se sincronizan con `fsync` y reemplazan el
  destino mediante movimiento atómico.
- Migrar en `Dispatchers.IO` todos los archivos regulares bajo
  `files/recordings`, preservando nombres y contenido lógico. La migración es
  idempotente y recupera respaldos, temporales cifrados y temporales planos de
  versiones anteriores.
- Exigir explícitamente un `SessionArtifactStore` a todos los productores y
  consumidores. No existe un valor por defecto plano en código de producción;
  la implementación plana solo se usa explícitamente en pruebas host.
- Propagar fallos de autenticación del checkpoint o la transcripción en vez de
  ocultar la sesión como si no existiera.

## Consecuencias

El audio y los artefactos dejan de ser interpretables fuera de la app sin la
clave de Keystore. La captura puede seguir escribiendo por bloques, la
recuperación calcula longitudes y hashes sobre plaintext autenticado y los
laboratorios ASR/VAD reciben streams descifrados sin crear copias planas en
disco.

Cada archivo añade 44 bytes de cabecera y cada frame añade 32 bytes. Abrir para
append autentica los frames existentes; los segmentos PCM nuevos no se
reabren, mientras que el journal incremental es pequeño. La migración inicial
lee, cifra y verifica los archivos y por eso se ejecuta fuera del hilo
principal.

Perder la clave de Keystore hace irrecuperables los artefactos por diseño. La
app conserva el ciphertext, reporta el error y no genera una clave sustituta.
La futura exportación deberá descifrar de forma explícita hacia un destino
elegido por el usuario y aplicar su propia protección.

## Fuentes

- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [Criptografía en Android](https://developer.android.com/privacy-and-security/cryptography)
- [Estado de security-crypto](https://developer.android.com/jetpack/androidx/releases/security)
