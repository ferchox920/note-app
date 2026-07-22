# ADR-000: Base del proyecto Android

- Estado: aceptada para el spike
- Fecha: 2026-07-21

## Contexto

El repositorio contenía solamente el plan y el documento maestro. El spike exige
un proyecto Android mínimo reproducible antes de integrar AudioRecord, VAD y
whisper.cpp.

## Decisión

- Kotlin nativo y Jetpack Compose.
- JDK 17, Android SDK 36, AGP 8.13.2 y Gradle 8.13.
- `minSdk 28` y `targetSdk 36` durante el spike.
- Módulos iniciales: `app`, `core-domain`, `core-audio`, `core-storage`,
  `feature-recording`, `benchmark` y `shared-testing`.
- Los estados de sesión viven en `core-domain` y se modifican mediante intents y
  un reducer puro. La UI consume un `StateFlow` desde un `ViewModel`.
- Navegación single-activity mediante Navigation Compose 2.9.8.
- Inyección mediante Hilt 2.57.1 y KSP 2.2.21-2.0.5. `RecordingViewModel` recibe
  controller, checkpoint store y runner ASR por constructor.
- C++/JNI queda aislado en módulos de inferencia; `whisper.cpp` y WebRTC VAD son
  las únicas rutas nativas actuales.

## Consecuencias

La estructura admite implementar captura y persistencia sin acoplarlas a la UI.
AGP 9 se evaluará después del spike: migrarlo ahora no aporta evidencia para G0.
Lifecycle se fija en 2.10.0 porque 2.11.0 exige `compileSdk 37` y AGP 9.1 o
superior, fuera de la toolchain estable elegida para este incremento.
AndroidX Hilt se fija en 1.3.0 porque 1.4.0 requiere `compileSdk 37` y AGP 9.2.
Hilt/Dagger 2.57.1 se conserva porque la línea 2.59+ exige AGP 9; estas retenciones
son deliberadas y explican los avisos de versión de lint.

Esta ADR no decide el formato final de audio, sample rate, VAD ni modelo ASR; esas
decisiones requieren medición en dispositivo real y quedarán en ADR-001.
