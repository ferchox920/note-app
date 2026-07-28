# G0.1 — revalidación optimizada en Galaxy S25 Ultra (2026-07-28)

## Decisión

**CONTINUAR.** La repetición con whisper.cpp/ggml optimizado supera la puerta G0.
Tiny y base procesaron el mismo audio autorizado de 357 s con RTF muy inferior a
1, primer texto menor a 3 s, estado térmico 0 y sin servicios remotos.

Este reporte es sanitizado: no contiene audio ni texto transcrito.

## Build y configuración

| Campo | Valor |
|---|---|
| Dispositivo | Galaxy S25 Ultra `SM-S938B` |
| Android | 16 / API 36 |
| App | `0.1.0-spike-benchmark` |
| APK | 136.555.856 bytes |
| SHA-256 APK | `182d0629de9031a08c73795e0f57fb012ec18b5e6445cad957209f22f584e50f` |
| CMake | `RelWithDebInfo` |
| ggml-cpu | `-O2 -DNDEBUG` |
| Wrapper JNI | `-O3 -DNDEBUG` |
| CPU reportada | NEON, ARM_FMA y REPACK activos |
| Configuración | 4 hilos, chunks máximos de 30 s |

La preparación automatizada se ejecutó mediante
[`../../../tools/prepare-g0-benchmark.ps1`](../../../tools/prepare-g0-benchmark.ps1)
y fallaría si las flags optimizadas no estuvieran presentes.

## Resultados comparables

| Modelo | Chunks | Audio útil | Inferencia | Primer texto | RTF | PSS pico | Térmica | Batería máx. |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| tiny q5_1 | 16 | 357.000 ms | 54.680 ms | 1.264 ms | **0,153** | 388.459 KiB | 0 | 27,0 °C |
| base q5_1 | 16 | 357.000 ms | 68.172 ms | 2.655 ms | **0,191** | 491.469 KiB | 0 | 29,9 °C |

Respecto de la APK debug:

- tiny pasó de RTF 5,34 a 0,153;
- base pasó de RTF 6,91 a 0,191;
- la cantidad de chunks y segmentos transcritos permaneció igual.

La diferencia demuestra que el cuello de botella era el código ggml sin
optimización, no una inviabilidad del S25 Ultra ni de whisper.cpp.

## Integridad reutilizada

La sesión `f9942c09-add4-4936-b50d-7f335c7f0be6` conserva:

- 6:18 de PCM directo a 16 kHz;
- tres segmentos y 12.108.800 bytes;
- 127 segmentos WebRTC VAD;
- cero errores de lectura;
- cero discontinuidades y cero frames faltantes;
- checksums válidos para todo el PCM.

La extracción privada del 2026-07-28 verificó simultáneamente los resultados debug
y benchmark. El evaluador selecciona explícitamente el menor RTF válido por modelo
cuando coexisten varias configuraciones.

## Revisión de puerta

Todos los chequeos automáticos de G0 resultaron verdaderos:

- S25 Ultra;
- sesión autorizada de al menos cinco minutos;
- captura íntegra;
- VAD con segmentos;
- tiny y base completados;
- al menos un RTF ≤ 1;
- telemetría de memoria y térmica presente.

La revisión humana de calidad textual continúa dentro del corpus de Sprint 2 y no
bloquea el avance a la prueba de captura G1. No se interpreta esta medición offline
como aprobación automática de G2 incremental.
