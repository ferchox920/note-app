# Evidencia: escrituras atómicas, checksums y temporales

## Resultado

**APROBADO (2026-07-31).** La garantía se implementó de forma transversal al
cifrar los artefactos de sesión y la base de datos. Los archivos autoritativos
solo se sustituyen después de sincronizar y verificar su candidato; la
recuperación descarta o consolida estados temporales de forma determinista.

## Garantías implementadas

### Artefactos de sesión

- `EncryptedSessionArtifactStore.writeBytesAtomically` escribe en
  `.secure-write.tmp`, sincroniza el descriptor, autentica el archivo completo y
  lo sustituye mediante `ATOMIC_MOVE + REPLACE_EXISTING`.
- Si el sistema de archivos no permite reemplazo atómico, la operación falla
  cerrada; no degrada una escritura sensible a una copia parcial.
- Cada frame usa AES-256-GCM con secuencia y ruta lógica autenticadas. Una
  alteración, truncamiento intermedio o intercambio de ruta se rechaza.
- Un tail append-only incompleto solo puede truncarse hasta el último frame GCM
  autenticado; ningún frame previo se reescribe.

### Audio y transcripción incremental

- Cada segmento PCM finalizado persiste tamaño, offsets y SHA-256 en el
  checkpoint.
- La recuperación recalcula el SHA-256 y rechaza un segmento listado alterado.
- Un segmento huérfano íntegro se adopta una sola vez y queda incorporado al
  siguiente checkpoint.
- El checkpoint y el materializado incremental usan escritura atómica; el
  diario append-only solo conserva entradas confirmadas por el checkpoint.

### Room y SQLCipher

- La migración crea una base cifrada temporal, ejecuta
  `PRAGMA integrity_check`, verifica que no sea SQLite plano y recién entonces
  intercambia los archivos.
- Los estados `.encrypted.tmp` y `.plaintext.backup` se recuperan de manera
  idempotente. El backup plano solo se elimina después de autenticar la base
  promovida.

## Limpieza controlada

Al inicializar el almacenamiento, la recuperación trata explícitamente:

- `.secure-write.tmp`: escritura nueva no confirmada; se descarta y se conserva
  el objetivo anterior.
- `.encrypted.tmp`: candidato de migración; se promueve únicamente si autentica
  para la ruta esperada.
- `.plaintext.backup`: se restaura si la promoción falta o está corrupta y luego
  vuelve a cifrarse.
- `.tmp` legado: se descarta si ya existe un objetivo; si es el único candidato,
  se consolida y entra inmediatamente en la migración cifrada.
- Tail GCM incompleto: se recorta hasta el último frame autenticado y se
  sincroniza antes de continuar.

Toda eliminación comprueba el resultado. Un fallo de borrado detiene la
inicialización en lugar de declarar falsamente una limpieza completa.

## Pruebas reproducibles

Las suites cubren, entre otros, estos casos:

- checkpoint reemplazado atómicamente y ausencia de `.tmp`;
- rechazo de PCM listado cuyo contenido ya no coincide con su SHA-256;
- recuperación idempotente de un frame final cifrado interrumpido;
- descarte del tail del diario no confirmado por el checkpoint;
- rollback del diario cuando falla el checkpoint;
- recuperación de backup-only, temporal cifrado verificado y ciphertext
  promovido corrupto;
- migración SQLCipher interrumpida con solo backup plano.

Comandos reproducibles:

```powershell
.\gradlew.bat `
  :core-security:testDebugUnitTest `
  :core-audio:testDebugUnitTest `
  :inference-asr:testDebugUnitTest `
  :core-storage:testDebugUnitTest `
  :core-storage:assembleDebugAndroidTest `
  --no-daemon --max-workers=1
```

La auditoría física de cifrado en el S25 Ultra autenticó dos veces 114
artefactos y 24 segmentos PCM de 14 sesiones. Encontró cero archivos planos,
desconocidos o temporales y verificó todos los offsets y SHA-256 PCM. No borró
datos, no grabó audio y no ejecutó ASR. Véase
[`encrypted-session-artifacts-2026-07-31.md`](encrypted-session-artifacts-2026-07-31.md).

## Decisión

Se cierra el P0 de escrituras atómicas, checksums y limpieza controlada de
temporales. Esta decisión no aprueba G3 por sí sola: siguen pendientes la
validación con reinicio real, BiometricPrompt opcional y borrado completo y
verificable de una sesión.
