# Protocolo físico: recuperación idempotente tras reinicio

## Objetivo

Validar en el S25 Ultra que una sesión interrumpida puede recuperarse de forma
idempotente después de un reinicio real del dispositivo, sin reescribir el
prefijo cifrado válido y sin afectar las sesiones de producción.

La auditoría no abre el micrófono, no ejecuta ASR y no utiliza grabaciones
existentes. Crea una sesión PCM sintética dentro de
`files/sprint4-reboot-recovery-audit`, fuera de `files/recordings`, y elimina
exclusivamente ese directorio al terminar.

## Ejecución

Con el teléfono conectado, autorizado y sin una grabación activa:

```powershell
$adb = 'C:\Users\ferna\AppData\Local\Android\Sdk\platform-tools\adb.exe'
.\tools\verify-s4-reboot-recovery.ps1 `
  -Adb $adb `
  -Serial R5CY20HYBGJ `
  -Execute
```

`-Execute` es obligatorio porque la auditoría reinicia el dispositivo. Cuando
el teléfono vuelva a encender, debe desbloquearse antes de que expire el tiempo
de espera para que Android permita leer el almacenamiento cifrado por
credenciales.

El arnés instala la APK debug como actualización mediante `adb install -r -t`.
No desinstala `com.noteapp`, no ejecuta `pm clear` y no elimina sesiones reales.

## Caso reproducible

1. Comprueba que el S25 está autorizado y que `AudioCaptureService` no está
   activo.
2. Registra únicamente cantidad y bytes almacenados bajo `files/recordings`.
3. Crea un primer segmento sintético cifrado de 32.000 bytes y lo incorpora al
   checkpoint.
4. Crea un segundo segmento cifrado de 48.000 bytes, lo sincroniza y deja el
   checkpoint sin esa entrada para representar un cierre abrupto.
5. Fuerza el cierre del proceso y reinicia físicamente el dispositivo.
6. Confirma que cambió el identificador de arranque y espera a que el usuario
   esté desbloqueado.
7. Recupera la sesión tres veces: dos antes de consolidar el checkpoint y una
   después. Todas deben producir el mismo prefijo de dos segmentos y 80.000
   bytes.
8. Verifica que el contenido cifrado válido no cambió durante las recuperaciones
   repetidas.
9. Simula una reanudación sin micrófono con 16.000 bytes sintéticos, finaliza la
   sesión y comprueba tres segmentos contiguos y 96.000 bytes.
10. Elimina el directorio aislado, vuelve a inventariar las sesiones reales y
    ejecuta la auditoría completa de artefactos cifrados.

## Criterios de aceptación

- El identificador de arranque cambia entre las dos fases.
- La sesión interrumpida aparece como recuperable tras el reinicio.
- El segmento huérfano cifrado se adopta una sola vez.
- Repetir la recuperación antes y después del checkpoint produce el mismo
  resultado.
- Ningún archivo cifrado válido del prefijo es reescrito.
- La sesión sintética puede reanudarse y finalizarse de forma segura.
- No quedan temporales, texto plano ni el directorio aislado de auditoría.
- Cantidad y bytes de `files/recordings` son idénticos antes y después.
- La auditoría completa de cifrado vuelve a aprobarse después del reinicio.
- `AudioCaptureService` permanece inactivo y la auditoría no inicia ASR.

El resultado sanitizado se guarda bajo `artifacts/private/sprint-4/`, excluido
de Git. Solo incluye conteos, tamaños y datos del entorno; nunca audio,
transcripciones, identificadores de sesiones reales ni contenido privado.
