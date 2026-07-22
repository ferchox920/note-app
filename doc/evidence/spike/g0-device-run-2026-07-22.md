# G0 — ejecución física en Galaxy S25 Ultra (2026-07-22)

## Estado

**CERRADA — decisión: AJUSTAR.** AudioRecord, WebRTC VAD y Whisper tiny/base
funcionaron completamente offline sobre el mismo audio. La captura fue íntegra,
pero ninguna configuración alcanzó un RTF cercano a 1.

Este reporte es sanitizado: no contiene audio ni texto transcrito.

## Dispositivo y build verificados

| Campo | Valor |
|---|---|
| Serial ADB | `R5CY20HYBGJ` |
| Modelo | `SM-S938B` (Galaxy S25 Ultra) |
| Android | 16, API 36 |
| ABI | `arm64-v8a` |
| Build del dispositivo | `samsung/pa3qxxx/pa3q:16/BP4A.251205.006/S938BXXSBCZG3_OWOBCZG3:user/release-keys` |
| App | `com.noteapp`, `0.1.0-spike` (`versionCode 1`) |
| APK debug | 138.541.143 bytes |
| SHA-256 APK | `73727ad777dafe2e0cd6573330442d45e78ab6460579f7d32d8133e65002ce74` |

ADB se ejecutó desde la ruta absoluta:

```text
C:\Users\ferna\AppData\Local\Android\Sdk\platform-tools\adb.exe
```

La comprobación previa confirmó estado `device`, actividad
`com.noteapp/.MainActivity`, permiso de micrófono y permiso de notificaciones.

## Modelos instalados

| Modelo | Bytes | SHA-256 |
|---|---:|---|
| `whisper-tiny-multilingual-q5_1` | 32.152.673 | `818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7` |
| `whisper-base-multilingual-q5_1` | 59.707.625 | `422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898` |

Los dos archivos fueron verificados tras la transferencia e importados a
`files/models/` de la app. No se registra contenido privado en este reporte.

## Condiciones iniciales observadas

- Batería: 36 %, conectada por USB.
- Estado térmico Android: 0 (sin throttling reportado).
- Temperatura de batería observada: 29,4 °C.
- Espacio libre en `/data`: aproximadamente 74 GiB.
- Captura elegida para el baseline G0: PCM16 mono directo a 16 kHz.
- Backend ASR: whisper.cpp v1.8.6, CPU-only.

## Sesión física ejecutada

- [x] Lectura, conversación libre y silencios autorizados durante 6:18.
- [x] Pausa de diez segundos y reanudación.
- [x] Pantalla apagada durante ochenta segundos mientras continuó la captura.
- [x] Checkpoint final `COMPLETED`.
- [x] Tiny y base sobre exactamente los mismos 357 s de chunks VAD.
- [x] Extracción y verificación automatizada.
- [ ] Revisión humana de calidad textual; no cambia la decisión de rendimiento.

El cierre produjo tres segmentos PCM, 12.108.800 bytes, 127 segmentos VAD, cero
errores de lectura, cero discontinuidades y cero frames faltantes estimados. Los
checksums de los tres PCM coincidieron con el checkpoint.

## Resultados ASR sanitizados

| Modelo | Chunks | Primer texto | Inferencia | RTF | PSS pico | Térmica máx. | Batería máx. |
|---|---:|---:|---:|---:|---:|---:|---:|
| tiny q5_1 | 16 | 51.716 ms | 1.905.629 ms | 5,34 | 393.695 KiB | 1 | 39,2 °C |
| base q5_1 | 16 | 118.294 ms | 2.466.804 ms | 6,91 | 477.245 KiB | 0 | 36,3 °C |

La transcripción permaneció privada y no forma parte de este reporte.

## Incidencias reales encontradas y corregidas

- La pausa podía bloquearse porque `AudioRecord.stop()` competía con una lectura
  bloqueante; la captura pasó a lectura no bloqueante y el recorder se libera en
  su coroutine propietaria.
- La recuperación fallaba por una expresión regular incompatible con ICU Android;
  el checkpoint ahora usa un parser controlado y la sesión interrumpida se recuperó.
- Se impidieron ASR simultáneos con un mutex de proceso y actividad `singleTask`.
- El recolector usaba una API de argumentos no disponible en Windows PowerShell;
  se hizo compatible y la evidencia final verificó correctamente.

## Comando de extracción reproducible

```powershell
.\tools\collect-device-session.ps1 `
  -SessionId <sessionId> `
  -Adb 'C:\Users\ferna\AppData\Local\Android\Sdk\platform-tools\adb.exe' `
  -Serial R5CY20HYBGJ `
  -RequireAsrModel @(
    'whisper-tiny-multilingual-q5_1',
    'whisper-base-multilingual-q5_1'
  )
```

## Regla de decisión

- **Continuar:** audio/VAD/tiny/base integrados sin pérdida o desorden y al menos una
  configuración útil con RTF cercano o inferior a 1.
- **Ajustar:** integración correcta, pero rendimiento o calidad fuera del presupuesto.
- **Reestimar:** fallo de integración o ninguna configuración cercana al objetivo.

La evaluación automática sólo determina elegibilidad para revisión. La decisión
final requiere confirmar consentimiento, condiciones de captura y calidad del texto.

## Decisión

**AJUSTAR.** La integración offline es funcional y la captura baseline es fiable,
pero tiny (RTF 5,34) y base (RTF 6,91) incumplen el presupuesto. Antes de avanzar a
G1/G2 debe optimizarse el backend/chunking/threading o evaluarse otro runtime. No se
acepta todavía Whisper CPU-only actual como ASR incremental del producto.
