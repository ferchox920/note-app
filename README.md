# Note App

MVP Android local-first para grabación, transcripción offline y notas estructuradas.

La implementación sigue [PLAN_DE_SPRINTS.md](PLAN_DE_SPRINTS.md), con
[`doc/DocMaster.md`](doc/DocMaster.md) como documento rector.

## Empezar aquí

- Para abrir, instalar y usar la APK de laboratorio en el S25 Ultra, seguir
  [`doc/GUIA_DE_USO.md`](doc/GUIA_DE_USO.md).
- Para conocer el orden de trabajo, leer primero
  [`PLAN_DE_SPRINTS.md`](PLAN_DE_SPRINTS.md) y consultar
  [`doc/DocMaster.md`](doc/DocMaster.md) cuando el plan remita a decisiones de producto.
- La ejecución física más reciente y su decisión están en
  [`doc/evidence/spike/g0-device-run-optimized-2026-07-28.md`](doc/evidence/spike/g0-device-run-optimized-2026-07-28.md).
- El protocolo reproducible de la build optimizada está en
  [`doc/evidence/spike/g0-optimized-benchmark-protocol.md`](doc/evidence/spike/g0-optimized-benchmark-protocol.md).
- La siguiente puerta activa es G1. Su matriz física de 90 minutos, recuperación
  y llamada está en
  [`doc/evidence/sprint-1/g1-device-gate-protocol.md`](doc/evidence/sprint-1/g1-device-gate-protocol.md).

## Requisitos

- JDK 17
- Android SDK 36

## Verificación local

En PowerShell:

```powershell
.\gradlew.bat build lint test
```

La primera fase es el spike técnico. La medición debug inicial quedó en
**AJUSTAR**, pero la auditoría descubrió ggml sin optimización. G0.1 repitió el
mismo audio con la variante `benchmark`: tiny/base obtuvieron RTF 0,153/0,191 y
la decisión técnica cambió a **CONTINUAR**.

La build actual captura PCM16 mono mediante un foreground service y permite comparar
16 kHz directo con 48 kHz normalizado a 16 kHz. Guarda segmentos privados con
checksum y checkpoint recuperable; la selección definitiva sigue pendiente del
Galaxy S25 Ultra según ADR-001.

Las sesiones nuevas guardan también un diario técnico inmutable de inicio, pausa,
reanudación, recuperación y cierre. El verificador puede exigir estos eventos sin
incluir audio ni texto en el reporte sanitizado.

WebRTC VAD procesa frames de 20 ms y Silero frames de 32 ms sobre el mismo PCM; el
runner de comparación persiste timelines separados. Los parámetros de captura,
detector y endpointing siguen siendo provisionales hasta evaluarlos contra intervalos
humanos y medirlos en el dispositivo objetivo.

El laboratorio ASR integra `whisper.cpp` v1.8.6 por NDK/JNI y permite comparar
tiny/base/small `q5_1` sobre la misma sesión VAD. Los modelos no se versionan como
binarios: se descargan y verifican con:

```powershell
.\tools\download-whisper-models.ps1 -Model all
```

El procedimiento de dispositivo está en
[`doc/evidence/spike/asr-lab-protocol.md`](doc/evidence/spike/asr-lab-protocol.md).
La evidencia sanitizada de G0 está enlazada en «Empezar aquí».

Sprint 3 dispone ya de un modo incremental de laboratorio dentro del foreground
service: ring buffer PCM, ventanas de 4 s cada 3 s, cola acotada que prioriza cierres,
reconciliación de overlap, texto estable/provisional y persistencia recuperable. G2
depende de cerrar G0/G1 y medir 45 minutos en hardware siguiendo
[`doc/evidence/sprint-3/incremental-transcription-protocol.md`](doc/evidence/sprint-3/incremental-transcription-protocol.md).

La ejecución reproducible y la matriz de decisión sanitizada para G0–G2 están en
[`doc/evidence/device-gates/README.md`](doc/evidence/device-gates/README.md).
