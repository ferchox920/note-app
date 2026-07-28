# Guía rápida de la APK de laboratorio

Esta es la guía operativa. El orden de implementación está en
[`../PLAN_DE_SPRINTS.md`](../PLAN_DE_SPRINTS.md) y las decisiones rectoras del MVP
en [`DocMaster.md`](DocMaster.md).

## Abrir la app ya instalada

En el Galaxy S25 Ultra, desbloquear el teléfono y tocar el icono **Note App**.
También puede abrirse desde PowerShell con el teléfono conectado y autorizado:

```powershell
$adb = 'C:\Users\ferna\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb -s R5CY20HYBGJ shell am start -n com.noteapp/.MainActivity
```

Los modelos tiny y base ya están importados en el almacenamiento privado de este
S25 Ultra. No hace falta reimportarlos mientras no se desinstale la app ni se
borren sus datos.

## Instalar o actualizar la APK

Desde la raíz del proyecto:

```powershell
.\gradlew.bat :app:assembleDebug
$adb = 'C:\Users\ferna\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb -s R5CY20HYBGJ install -r .\app\build\outputs\apk\debug\app-debug.apk
& $adb -s R5CY20HYBGJ shell am start -n com.noteapp/.MainActivity
```

`install -r` conserva los datos y modelos existentes. Una desinstalación completa
elimina grabaciones y modelos privados.

Para mediciones ASR no usar `assembleDebug`. Preparar e instalar la variante
optimizada con:

```powershell
.\tools\prepare-g0-benchmark.ps1 `
  -Adb 'C:\Users\ferna\AppData\Local\Android\Sdk\platform-tools\adb.exe' `
  -Serial R5CY20HYBGJ `
  -Install
```

## Grabar una sesión

1. Elegir **Sin ASR en vivo** para una prueba de captura, o elegir tiny/base para
   habilitar el modo incremental experimental.
2. Tocar **Iniciar 16 kHz**. La ruta 48→16 kHz sigue siendo comparativa y aún no
   es la ruta recomendada.
3. Conceder micrófono y notificaciones si Android los solicita.
4. Usar **Pausar**, **Reanudar** y finalmente **Finalizar**.
5. Comprobar que el estado sea `COMPLETED`, con cero errores de lectura y cero
   discontinuidades.

## Transcribir una sesión finalizada

Desplazarse hasta **Laboratorio ASR** y tocar **Transcribir tiny** o
**Transcribir base**. Ejecutar sólo una transcripción a la vez y mantener la app
visible: el build actual de laboratorio no garantiza que el postprocesado sobreviva
al bloqueo de pantalla. No medir rendimiento con `assembleDebug`: esa variante
tardó 32/41 minutos. La variante `benchmark` optimizada procesó el mismo audio en
55/68 segundos con tiny/base.

La grabación y las transcripciones son privadas. No compartir el contenido de
`artifacts/private/` sin consentimiento explícito.
