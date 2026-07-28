# G0.1 — benchmark ASR nativo optimizado

## Objetivo

Repetir G0 con whisper.cpp/ggml compilado como `RelWithDebInfo`, evitando que una
APK debug sin optimización determine la viabilidad del ASR. La variante
`benchmark` conserva firma y acceso `run-as` de laboratorio, pero resuelve todas
las bibliotecas Android contra `release`.

## Preparación verificable

Con el S25 Ultra conectado y autorizado:

```powershell
.\tools\prepare-g0-benchmark.ps1 `
  -Adb 'C:\Users\ferna\AppData\Local\Android\Sdk\platform-tools\adb.exe' `
  -Serial R5CY20HYBGJ `
  -Install
```

El script falla si `ggml-cpu` no contiene `-O2`/`-O3` y `-DNDEBUG`, o si el
wrapper JNI no contiene `-O3` y `-DNDEBUG`. También ejecuta tests ASR, lint,
genera el APK, registra bytes/SHA-256, instala preservando datos y abre la app.

## Matriz escalonada

Usar siempre la sesión autorizada G0 y comenzar con estado térmico 0. No ejecutar
dos configuraciones simultáneas.

1. Tiny, chunks de 30 s: 2, 4, 6 y 8 hilos.
2. Elegir el menor RTF sostenible.
3. Con ese número de hilos, comparar chunks de 10, 20 y 30 s.
4. Ejecutar base sólo con la mejor configuración tiny.

La UI muestra la configuración activa. Cada resultado usa un nombre distinto:

```text
asr-result-<modelo>-t<hilos>-c<segundos>s.json
```

El JSON schema 2 conserva `benchmarkConfigId`, hilos, chunk máximo,
`nativeSystemInfo` y tiempos nativos de sample/encode/decode/batch/prompt. El
verificador sanitizado no incluye audio ni texto.

## Criterio

- **Continuar:** al menos una configuración alcanza RTF ≤ 1 o suficientemente
  cercano con estabilidad térmica y memoria aceptable.
- **Ajustar backend:** la build optimizada mejora de forma importante, pero sigue
  fuera del presupuesto.
- **Reestimar:** no existe mejora material o la estabilidad se degrada.

Después de cada corrida:

```powershell
.\tools\collect-device-session.ps1 `
  -SessionId f9942c09-add4-4936-b50d-7f335c7f0be6 `
  -Adb 'C:\Users\ferna\AppData\Local\Android\Sdk\platform-tools\adb.exe' `
  -Serial R5CY20HYBGJ `
  -RequireAsrModel whisper-tiny-multilingual-q5_1
```
