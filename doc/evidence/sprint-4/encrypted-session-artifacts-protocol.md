# Protocolo físico: cifrado de artefactos de sesión

## Objetivo

Validar en el S25 Ultra que todos los archivos bajo `files/recordings` migran a
AES-256-GCM sin perder sesiones, que Android Keystore conserva la clave entre
arranques y que PCM, checkpoints, VAD, transcripciones y resultados siguen
autenticando y siendo legibles dentro de la app.

Este caso utiliza únicamente sesiones ya existentes. No inicia grabación, ASR,
refinamiento ni retranscripción.

## Ejecución

Con el teléfono conectado, desbloqueado y autorizado por ADB:

```powershell
$adb = 'C:\Users\ferna\AppData\Local\Android\Sdk\platform-tools\adb.exe'
.\tools\verify-s4-encrypted-artifacts.ps1 `
  -Adb $adb `
  -Serial R5CY20HYBGJ `
  -Execute
```

El switch `-Execute` es obligatorio porque el arnés instala la APK debug como
actualización y migra los datos privados. Usa exclusivamente `adb install -r`;
nunca desinstala `com.noteapp`, ejecuta `pm clear` ni elimina sesiones. Al
terminar solo desinstala el APK de pruebas `com.noteapp.test`.

## Comprobaciones automatizadas

1. Confirma dispositivo autorizado y acceso `run-as` al paquete existente.
2. Registra únicamente cantidad, tamaño y magic de los archivos previos, sin
   copiar audio o texto.
3. Compila la APK y el APK instrumentado e instala ambos preservando datos.
4. Abre la app y espera hasta que todos los archivos tengan magic `NAARTF01`.
5. Dentro del UID de la app:
   - descifra y autentica todos los frames;
   - verifica SHA-256 y offsets de cada PCM contra el checkpoint;
   - valida estados, tamaños y secuencias;
   - exige archivos `0600` y directorios `0700`;
   - rechaza temporales o respaldos residuales;
   - ejecuta la migración una segunda vez y comprueba que no reescriba nada.
6. Fuerza un cierre, abre nuevamente la app y repite la auditoría.

La prueba instrumentada emite solo conteos y bytes totales. No incluye nombres
de sesión, transcripciones, resultados ASR ni contenido PCM.

## Criterios de aceptación

- Hay al menos una sesión persistida y todos sus artefactos autentican.
- Cero archivos quedan en formato plano o desconocido.
- Cero `.tmp`, `.secure-write.tmp`, `.encrypted.tmp` o
  `.plaintext.backup` permanecen.
- Los checksums PCM y offsets del checkpoint coinciden.
- La segunda migración no cambia tamaño ni fecha de modificación.
- El segundo arranque conserva las mismas sesiones.
- La app no muestra errores de almacenamiento, Keystore ni
  `SESSION_NOT_INDEXED`.

El arnés deja un reporte sanitizado bajo
`artifacts/private/sprint-4/`, ignorado por Git. Solo después de revisar ese
resultado se crea la evidencia versionada y se marca el P0 como completado en
`PLAN_DE_SPRINTS.md`.
