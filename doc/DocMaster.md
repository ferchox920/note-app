# Documento maestro para un MVP Android local de transcripción y notas estructuradas

## Resumen ejecutivo y definición exacta del MVP

Sí, **el MVP es técnicamente viable** en un Galaxy S25 Ultra y un Galaxy S25+ si se recorta el alcance con disciplina quirúrgica: **grabación robusta**, **segmentación por voz/silencio**, **transcripción local incremental en español con latencia tolerable**, **persistencia local cifrada**, y **generación final de notas estructuradas también local**. Lo viable hoy no es “magia agente total”; lo viable es un pipeline serio, offline-first, medible y con una UX honesta sobre sus límites. Los S25 usan Snapdragon 8 Elite for Galaxy, tienen 12 GB de RAM en las configuraciones generales de S25+ y Ultra, almacenamiento UFS rápido y soporte moderno de GPU/Vulkan; además, Qualcomm expone CPU, GPU y Hexagon NPU a través de varias rutas de inferencia, aunque **no toda la aceleración NPU está garantizada ni es igualmente accesible desde cualquier runtime**. Android, por su parte, deprecó NNAPI en Android 15, así que diseñar hoy “pensando en NNAPI” como eje principal sería empezar la casa por la ventana y encima con la ventana rota. citeturn23view0turn25view0turn32search11turn30view0turn30view0turn12search5turn23view4turn20search7turn37search16

La **definición recomendada del MVP** no debe intentar resolver desde el día uno diarización robusta, traducción, edición semántica avanzada, sincronización escritorio-tiempo-real, ni integración con APIs privadas de Samsung o Galaxy AI. El MVP debe hacer muy bien estas funciones: crear una sesión, grabar 45–90 minutos con pantalla apagada, pausar/reanudar, sobrevivir a interrupciones razonables, detectar voz y silencios, transcribir localmente español de forma progresiva por bloques cortos, guardar audio/transcripción/nota/metadatos localmente, permitir edición manual, buscar sesiones y exportar a texto/Markdown. La **nota estructurada** debe generarse al final de la conversación, no durante ella. La diarización, si aparece, debe ser manual o experimental. Esa poda no es derrota: es lo que separa un producto de un hobby barroco. citeturn23view5turn12search4turn17search2turn17search17turn27search0turn43search1

La elección dominante para este proyecto es **Android nativo con Kotlin y Jetpack Compose**, con arquitectura por capas, `ViewModel` como estado de negocio, `StateFlow`/Compose state para producción de UI, servicios en foreground para la grabación y un backend local modular dentro de la app. Compose, Kotlin y coroutines están alineados con la guía oficial de Android para estado, asincronía y arquitectura; Hilt reduce fricción de DI y Room/DataStore cubren lo que necesitas para datos estructurados y preferencias. React Native, Expo y Flutter pueden tocar nativo, sí, pero justo ahí está el problema: este proyecto vive en captura cruda de audio, inferencia local, foreground services, JNI, benchmarking, termal y seguridad. En otras palabras: el corazón del producto es nativo; meterle una capa cruzada al principio sería elegante como ponerle corbata a un martillo. citeturn34search5turn33search21turn33search0turn40search1turn40search3turn33search10turn43search1turn43search2turn35search1turn35search0turn34search1

### Alcance recomendado

| Categoría | Incluye en MVP | Motivo |
|---|---|---|
| Imprescindible | Grabación local confiable, foreground service, pausa/reanudación, recuperación de sesión, VAD, transcripción incremental local en español, timestamps, persistencia local cifrada, edición manual, búsqueda, exportación Markdown/TXT, benchmark de latencia/RAM/batería/temperatura | Es el núcleo del valor y puede validarse objetivamente |
| Posterior al MVP | Sincronización con escritorio, audio Bluetooth refinado, vocabulario personalizado, plantillas avanzadas, resumen progresivo durante grabación | Aporta mucho valor, pero no bloquea la validación inicial |
| Experimental | Diarización local, uso real de NPU para ASR/LLM, denoising agresivo, ASR realmente streaming palabra por palabra, selección dinámica de modelos | Alta incertidumbre técnica y riesgo de retraso |
| Deseable pero no crítico | Etiquetas automáticas, re-titulado automático, múltiples plantillas profesionales, exportación enriquecida | Mejora uso, no viabilidad |
| Evitar al inicio | Expo, microservicios locales, backend de escritorio desde el día uno, APIs privadas de Samsung, dependencia obligatoria de nube, diarización “prometida” | Introduce complejidad prematura o riesgo de bloqueo |

### MVP final propuesto

**Sesión local offline-first** que permite grabar una conversación presencial extensa, producir una transcripción incremental en español con timestamps y luego generar una nota estructurada editable, todo protegido localmente y validado empíricamente en S25 Ultra y S25+.

### Capacidades fuera de alcance del primer corte

Quedan fuera del primer corte: identificación fiable de interlocutores, resumen en vivo, sincronización inmediata con PC, aprovechamiento garantizado de la NPU, adaptación clínica automatizada con terminología sensible, traducción, captura multipista y promesas de “mejor que Samsung” antes de que los benchmarks hablen. Samsung sí ofrece en su app Voice Recorder transcripción, resumen y traducción para el usuario final, pero eso no equivale a una API pública reutilizable; en el portal de Samsung Developer sí existe Samsung Neural SDK, pero su última release pública visible es la 3.0 de mayo de 2021 y se apoya en APIs C++, no en un framework moderno de alto nivel pensado para este flujo de producto. Eso hace que su uso como base principal sea una apuesta arriesgada para un desarrollador individual. citeturn23view6turn23view2

## Hardware Samsung y viabilidad real de aceleración

La base material es buena. La serie Galaxy S25 se comercializó oficialmente con **Snapdragon 8 Elite for Galaxy**; Samsung lo presenta como el “procesador más potente para Galaxy” y Qualcomm describe en la plataforma Snapdragon 8 Elite una CPU Oryon con núcleo prime hasta 4.32 GHz, GPU Adreno con soporte de Vulkan 1.3 y OpenCL 3.0 FP, memoria LPDDR5x y un Hexagon NPU con soporte para múltiples precisiones, incluyendo INT4/INT8/INT16 y FP16. Samsung destaca además optimización Vulkan en la serie S25. Eso coloca a ambos dispositivos en una categoría muy favorable para inferencia local y procesamiento largo sostenido. citeturn23view0turn25view0turn30view0turn30view0

### Comparativa útil para el MVP

| Aspecto | Galaxy S25+ | Galaxy S25 Ultra | Relevancia para el MVP |
|---|---|---|---|
| SoC | Snapdragon 8 Elite for Galaxy citeturn23view0turn23view1 | Snapdragon 8 Elite for Galaxy citeturn25view0 | Mismo SoC base, así que el pipeline puede diseñarse una sola vez |
| RAM típica oficial | 12 GB citeturn23view0 | 12 GB en la mayoría de regiones; 16 GB en 1 TB en Hong Kong citeturn25view0turn32search11 | La RAM no es el cuello principal si el LLM se mantiene pequeño y cuantizado |
| Almacenamiento | 256/512 GB según región citeturn23view0 | 256/512 GB y hasta 1 TB citeturn25view0turn32search5 | Importa para sesiones largas, corpus y modelos |
| Batería típica | 4,900 mAh citeturn23view0 | 5,000 mAh citeturn25view0 | El Ultra tendrá más margen térmico y energético sostenido |
| Video playback oficial | hasta 30 h citeturn23view0 | hasta 31 h citeturn25view0 | Señal indirecta de eficiencia; no reemplaza tus pruebas |
| Soporte gráfico del SoC | Adreno + Vulkan 1.3/OpenCL 3.0 FP citeturn30view0 | Adreno + Vulkan 1.3/OpenCL 3.0 FP citeturn30view0 | Muy útil para delegates GPU |
| NPU del SoC | Hexagon NPU citeturn30view0 | Hexagon NPU citeturn30view0 | Potencial alto, acceso práctico variable |

El dato importante aquí no es solo “son potentes”, sino **que son suficientemente parecidos** como para que el MVP se optimice primero para un único perfil de hardware. Esa simetría reduce la fragmentación inicial: mismo SoC, misma familia Adreno, mismo stack Android moderno. Donde sí cambia la experiencia sostenida es en batería, almacenamiento y probabilidad de mejor disipación en el Ultra, por lo que conviene tratar al Ultra como **dispositivo de benchmark principal** y al Plus como **dispositivo de validación de sostenibilidad**. citeturn23view0turn25view0turn30view0turn27search0

### Qué se puede y qué no se puede asumir sobre GPU y NPU

No conviene asumir acceso directo a la NPU “porque el marketing dijo IA”. Android deprecó NNAPI en Android 15. Samsung ofrece un **Samsung Neural SDK** público, pero su documentación pública visible es vieja y centrada en C++/conversión de modelos. Qualcomm, por su lado, ofrece **AI Engine Direct/QNN**, y tanto ONNX Runtime como ExecuTorch documentan integración con QNN/Qualcomm AI Engine; LiteRT-LM documenta aceleración GPU y NPU en Android. Eso significa que **hay rutas públicas reales** para intentar aceleración, pero no todas sirven para todos los modelos, ni todos los operadores de todos los modelos se delegan completamente al acelerador. En la práctica, muchas ejecuciones terminan siendo híbridas: parte en acelerador, parte en CPU. citeturn12search5turn23view2turn37search16turn20search7turn19search23turn23view4

La consecuencia práctica es contundente: para el MVP debes diseñar el sistema de modo que **CPU sea un camino soportado y digno**, GPU sea la primera aceleración a explorar y NPU sea un bonus validado empíricamente, no una dependencia arquitectónica. La GPU en Snapdragon 8 Elite tiene soporte oficial de Vulkan 1.3 y OpenCL 3.0 FP; eso hace plausibles caminos vía LiteRT-LM, LiteRT y ciertos runtimes nativos. La NPU, en cambio, solo debe darse por “realmente utilizable” si tu benchmark demuestra menor latencia sostenida, menor energía por unidad de trabajo o ambas. citeturn30view0turn19search3turn19search12turn37search16turn20search5

### Cómo demostrar dónde corre la inferencia

Debes tratar “CPU/GPU/NPU” como una hipótesis falsable. El método correcto es combinar cuatro señales: selección explícita del backend en el runtime; logs/profiling del runtime; trazas de sistema; y métricas comparativas. ORT documenta execution providers; QNN/ExecuTorch documentan backends Qualcomm; Perfetto permite trazar actividad de CPU/GPU/sistema; y Android expone APIs térmicas para saber cuán cerca estás del throttling. Si un modelo “supuestamente usa NPU” pero la latencia no cambia, el consumo no baja y las trazas siguen castigando CPU, no estaba corriendo donde creías. La NPU no es un lugar espiritual: o deja huella o era humo. citeturn20search5turn20search7turn37search7turn18search0turn18search2turn18search16turn27search0

El protocolo que recomiendo es este:

| Prueba | Qué observar | Criterio |
|---|---|---|
| CPU-only | Latencia, RTF, energía, temperatura base | Establece el suelo |
| GPU delegate | Mejora de RTF y/o menor CPU sostenida | Si mejora sin sobrecalentar, es candidato real |
| QNN/NPU path | Logs del backend + perf comparativa + trazas | Solo se acepta si la mejora es reproducible |
| Stress test 45–90 min | Thermal headroom, estado térmico, throttling | Si colapsa en 20 min, no sirve aunque sea rápido al inicio |

## Stack recomendado y arquitectura operativa

La recomendación para la primera implementación es esta:

**Android app**: Kotlin, Jetpack Compose, `ViewModel` + UDF/MVVM pragmático, coroutines, Flow, Hilt, Room, Proto DataStore, Foreground Service para grabación, `AudioRecord` para captura PCM, JNI solo donde lo impongan los runtimes/modelos y un módulo de benchmark separado.  
**ASR inicial**: `whisper.cpp` integrado por JNI.  
**VAD inicial**: WebRTC VAD o Silero VAD, dependiendo del resultado del experimento corto.  
**LLM inicial para notas**: Gemma 3n E2B-it con LiteRT-LM si el rendimiento es suficiente; si no, fallback a un modelo más pequeño o a un pipeline híbrido extractivo + generativo más conservador.  
**Escritorio**: pospuesto hasta después del MVP.  
**Backend remoto**: ninguno obligatorio. citeturn34search5turn33search21turn40search1turn40search3turn33search10turn43search1turn43search2turn22search20turn11search0turn11search3turn21search0turn23view4turn39search0

### Por qué Kotlin nativo gana aquí

Android recomienda Kotlin, Compose y un flujo de estado observable con coroutines/Flow y state holders. Compose está muy bien alineado con un producto que pasa por estados claros: sesión inactiva, grabando, procesando chunks, texto parcial, texto estabilizado, resumen pendiendo, resumen listo, error recuperable. Además, el control fino que necesitas sobre foreground services, audio, permisos, almacenamiento local y JNI vive naturalmente en Android nativo. React Native y Flutter sí pueden invocar código nativo, pero justamente eso revela el costo: terminarías escribiendo la parte difícil en Kotlin/C++ de todos modos y agregando otra frontera tecnológica que también hay que depurar. Expo, en particular, está optimizado para velocidad de desarrollo general, no para un pipeline de audio y ML local tan exigente. citeturn34search5turn40search13turn33search0turn35search1turn35search0turn34search1turn35search6

### Cuándo usar C++ y cuándo evitarlo

C++ debe entrar solo por necesidad objetiva. En este proyecto, las razones legítimas para usarlo son tres: integración de `whisper.cpp`, integración de librerías de DSP/VAD/denoising nativas, y acceso a ciertos runtimes o SDKs que exigen NDK/JNI. Fuera de eso, evita C++ al principio. `AudioRecord`, Room, servicios, UI, orquestación del pipeline, colas, estado y almacenamiento deben empezar en Kotlin. Si metes C++ demasiado temprano, duplicas la superficie de fallos, complicas profiling, empaquetado y debugging, y conviertes cada cambio chico en una pequeña expedición arqueológica. citeturn11search3turn23view2turn37search16turn22search20

### Captura, workers e inyección de dependencias

La app no necesita microservicios locales. Necesita una **app modular** con un **servicio persistente de grabación** y un pipeline interno bien separado. `WorkManager` sirve para tareas diferibles y reintentables, pero la grabación en vivo y la transcripción incremental deben vivir bajo un foreground service y workers/coroutines administrados por la propia app. Android documenta foreground services para tareas visibles de larga duración y WorkManager como infraestructura distinta; mezclarlos sin criterio hace perder control. citeturn17search2turn33search0turn33search10

### Arquitectura modular recomendada

| Módulo | Responsabilidad principal |
|---|---|
| `app` | Ensamblado, navegación, DI, permisos, configuración global |
| `core-domain` | Casos de uso, entidades de dominio, contratos |
| `core-audio` | Captura, buffers, formato PCM, resampling, sesión de micrófono |
| `core-storage` | Room, DataStore, archivos, migraciones, índices |
| `core-security` | Llaves, biometría, cifrado de archivos y DB, políticas de exportación |
| `inference-vad` | VAD y endpointing |
| `inference-asr` | Carga de modelos, inferencia ASR, timestamps, reconciliación incremental |
| `inference-llm` | Extracción por bloques, consolidación y plantillas de notas |
| `feature-recording` | UX de grabación/en vivo |
| `feature-sessions` | Historial, búsqueda, reanudación, borrado |
| `feature-transcript` | Visualización/edición del texto |
| `feature-notes` | Plantillas, generación, edición de notas |
| `benchmark` | Benchmarks, perfiles, toggles de runtime/modelo |
| `shared-testing` | Fixtures, fakes, golden tests, corpus de pruebas |

### Flujo de componentes

```text
Micrófono
  -> AudioRecord / Foreground Service
  -> Ring buffer PCM
  -> VAD + endpointing
  -> ensamblador de chunks
  -> ASR incremental
  -> reconciliación de hipótesis
  -> segmentos de transcripción con timestamps
  -> persistencia local
  -> al finalizar: extractor por bloques
  -> consolidación de nota estructurada
  -> editor y exportación
```

### Decisión sobre escritorio futuro

No construyas el backend de escritorio ahora. El “doble producto” es un imán para el scope creep. El móvil ya tiene suficientes variables: audio, permisos, termal, batería, almacenamiento, inferencia local y UX de recuperación. Cuando el MVP móvil funcione, tendrás datos reales para decidir si el escritorio aporta valor. Si más adelante quieres uno, **Python con FastAPI** te da la ruta más corta para experimentación, herramientas de NLP y reutilización de lo que ya sabes; **Go** solo gana si el problema principal pasa a ser sincronización, streaming o un daemon muy concurrente de escritorio. Una arquitectura híbrida Go + Python puede ser buena en empresas con equipo, pero para un desarrollador solo aquí es complejidad prematura. citeturn36search0turn36search10turn36search4turn36search2

## Pipeline de audio, VAD y transcripción incremental

La captura debe arrancar con `AudioRecord`, no con `MediaRecorder`, porque necesitas acceso a PCM crudo y control de buffers. `MediaRecorder` es excelente para “grabá y guardá”, pero acá el audio no solo se graba: se trocea, se inspecciona, se filtra y se empuja a inferencia incremental. Android describe `MediaRecorder` como una API sencilla de grabación, mientras `AudioRecord` está pensado para capturar datos del hardware de audio que la app consume leyendo buffers. Esa diferencia es exactamente la frontera entre una grabadora y un producto de inteligencia local. citeturn22search14turn22search20

### Estrategia de audio recomendada

Mi recomendación inicial es una estrategia dual y pragmática:

| Capa | Decisión |
|---|---|
| Captura base | `AudioRecord` mono PCM16 |
| Frecuencia preferida de captura | probar 48 kHz nativo y 16 kHz directo; elegir por benchmark |
| Frecuencia interna para ASR/VAD | 16 kHz mono |
| Buffer frame | 20–30 ms para VAD; colas mayores para ASR |
| Chunk ASR incremental | 2–4 s visibles al usuario; 5–8 s para refinamiento |
| Overlap | 0.5–1.0 s |
| Archivo final | audio maestro comprimido y/o segmentos PCM temporales según benchmark |
| Política de silencio | VAD con hangover y endpointing conservador |
| Denoising | apagado por defecto; habilitar solo si mejora WER real |

Hay una razón técnica para no casarte de entrada con 16 kHz o 48 kHz. Muchos modelos ASR operan en 16 kHz, pero RNNoise trabaja a 48 kHz y la ruta nativa de muchos dispositivos rinde mejor cerca del sample rate del hardware. Así que en Fase 1 debes medir dos pipelines: **captura nativa 48 kHz + resample a 16 kHz** versus **captura directa 16 kHz**. Quédate con el que produzca mejor combinación de estabilidad, energía, calidad y simplicidad. Lo elegante aquí no es “más pro”; lo elegante es no resamplear por deporte. citeturn21search6turn22search0turn22search20

### VAD y preprocesamiento

`WebRTC VAD` sigue siendo rápido y sencillo; Silero VAD es pequeño, rápido y moderno; MarbleNet es interesante sobre el papel, pero añade más complejidad; RNNoise puede mejorar ambientes ruidosos, aunque cualquier procesamiento mal calibrado también puede degradar ASR. Silero reporta chunks de 30+ ms por debajo de 1 ms en CPU en condiciones favorables y un tamaño de unos 2 MB; RNNoise está diseñado como supresión en tiempo real sobre PCM mono 48 kHz; el paper de MarbleNet lo plantea como VAD eficiente, pero ya entra en otra liga de integración; y el VAD de WebRTC sigue siendo el clásico rápido para endpointing. La recomendación para el MVP es simple: **VAD primero, denoising después**. Muchas veces un buen VAD mejora más que un mal denoiser. citeturn21search0turn21search8turn21search6turn21search14turn21search3turn21search7

### Modelos ASR evaluados

| Opción | Licencia | Android | Streaming | Español | Comentario operativo |
|---|---|---|---|---|---|
| `whisper.cpp` + Whisper base/small cuantizado | MIT en `whisper.cpp` y MIT heredada por Whisper citeturn11search7turn38search4 | Sí, vía NDK/JNI citeturn11search3 | Pseudo-streaming por ventanas | Muy buen baseline multilingüe | Mejor punto de partida para calidad en español y madurez |
| Sherpa-ONNX | Apache 2.0 citeturn38search15 | Sí citeturn38search5turn38search11 | Sí, soporta streaming y non-streaming citeturn38search11 | Depende del modelo ONNX concreto | Excelente candidato alternativo si eliges bien el modelo |
| Vosk | Apache 2.0 en su ecosistema abierto | Sí | Parcial/streaming clásico | Español disponible históricamente, pero menor calidad típica | Buen fallback ultraligero, no mejor baseline |
| Moonshine | MIT en el paper/model card y ecosistema abierto; hoy conviene revisar repo/model card exactos | Android posible, pero menos estándar | Diseñado para live transcription citeturn38search18 | Aquí hay señales mixtas: el repo Moonshine Voice actual menciona español, pero el model card original de Useful Sensors era inglés/English-first citeturn38search1turn38search14 | Prometedor, pero todavía no lo tomaría como primera apuesta del MVP |
| Distil-Whisper | MIT heredada de Whisper en variantes derivadas citeturn38search4 | Móvil no es su camino natural | No es la mejor opción para este caso | La línea oficial Distil-Whisper es inglesa; su repo recomienda Whisper Turbo para multilingüe citeturn38search7turn38search13 | No la elegiría como primera opción para español móvil |
| Parakeet / NeMo multilingüe | Modelo grande, licencias del modelo a revisar caso por caso | Android no turnkey | Sí, según familia/modelo | Sí, incluye es-US y es-ES en tarjetas recientes citeturn38search6turn38search3 | Más útil como benchmark de laboratorio que como primera integración móvil |

### Selección recomendada de ASR

La mejor secuencia de decisión es esta:

| Rol | Recomendación |
|---|---|
| Modelo principal inicial | **Whisper base multilingüe cuantizado en `whisper.cpp`** |
| Modelo alternativo | **Sherpa-ONNX con un modelo streaming que soporte español y timestamps** |
| Modelo de respaldo | **Vosk** para validar pipeline y consumo mínimo |
| Modelo para benchmark de calidad | **Whisper small cuantizado** en el mismo dispositivo |
| Modelo para benchmark de latencia extrema | **Whisper tiny/base** |

La razón para arrancar con Whisper no es romanticismo open-source; es que Whisper sigue siendo el punto de referencia práctico para español conversacional, tiene ecosistema maduro y `whisper.cpp` existe precisamente para correr inferencia local con cuantización y setup mínimo en hardware variado. Su mayor debilidad para este producto no es la calidad, sino que el streaming es **pseudo-streaming**, no verdaderamente stateful. Eso obliga a resolver incrementalidad con ventanas deslizantes, overlap y reconciliación. Pero aún así es un mejor primer túnel que saltar directo a un stack “más puro” pero menos probado en español real. citeturn11search0turn11search3turn38search13turn38search7

### Cómo hacer transcripción incremental sin tartamudear texto

El nivel viable para el MVP no es palabra por palabra perfecta. Es **frase corta o bloque breve con estabilización**. El enfoque recomendado es:

1. Mantener un ring buffer de audio reciente.  
2. Correr VAD sobre frames de 20–30 ms.  
3. Cuando hay voz sostenida, acumular a una ventana de trabajo.  
4. Emitir hipótesis parciales cada 2–4 s.  
5. Reprocesar con overlap de 0.5–1 s.  
6. Confirmar solo el **prefijo estable**: lo que coincide en hipótesis sucesivas.  
7. Cerrar un segmento cuando haya silencio suficiente o límite de duración.  

Ese patrón se parece a lo que hoy exponen frameworks de speech modernos: la propia API GenAI Speech Recognition de ML Kit define salida continua con parciales que pueden cambiar antes de volverse finales, pero su modo avanzado es alpha y está limitado a Pixel 10; por eso sirve como referencia conceptual, no como base del MVP para S25. `SpeechRecognizer`, además, Android lo documenta como propenso a usar servidores remotos y explícitamente no pensado para reconocimiento continuo. citeturn23view5turn12search4

```text
while (sessionActive):
    pcm = audioRecord.read()
    ringBuffer.write(pcm)
    vadState = vad.process(frame20ms)

    if vadState == SPEECH:
        speechAssembler.append(pcm)

    if speechAssembler.readyForPartial():
        chunk = speechAssembler.window(lastNSeconds=4, overlap=1)
        hypothesis = asr.transcribe(chunk, timestamps=true)
        stablePrefix = reconcile(previousHypothesis, hypothesis)
        transcript.commitStable(stablePrefix)
        transcript.showUnstableTail(hypothesis.tail)
        previousHypothesis = hypothesis

    if speechAssembler.endpointDetected():
        finalChunk = speechAssembler.flush()
        finalText = asr.transcribe(finalChunk, refine=true)
        transcript.finalizeSegment(finalText)
        previousHypothesis = EMPTY
```

### Diarización

La diarización **no debe formar parte del MVP imprescindible**. En móvil local, diarización seria implica embeddings de hablante, clustering, segmentación robusta y más memoria/latencia. Para un producto de conversaciones largas y privadas, eso es una trampa clásica: cuesta mucho, falla bastante y distrae del problema principal, que es que el texto y la nota sean útiles. La decisión correcta es **posponerla** o volverla **experimental post-conversación**. En el primer corte, usa uno de estos enfoques: sin diarización; o marcación manual de hablante por segmentos; o heurística muy limitada de “Interlocutor A/B” editable por el usuario. MarbleNet y ecosistemas como sherpa-onnx sí muestran que el mundo offline local de speech tiene piezas para speaker tasks, pero no están en el punto de “enchufar y confiar” para este MVP. citeturn21search7turn38search15

## Notas estructuradas, seguridad y persistencia local

La nota final no debe surgir de un único prompt heroico sobre 90 minutos de texto. Debe salir de un **proceso jerárquico**. El camino correcto es: dividir la transcripción en bloques manejables, extraer estructura por bloque, conservar evidencia con timestamps y consolidar al final. Esto reduce alucinaciones y evita reventar la ventana de contexto. Modelos pequeños pueden resumir bien si el trabajo está bien partido; modelos pequeños haciendo malabares con una transcripción eterna suelen inventar con una tranquilidad que da escalofríos. citeturn39search0turn39search1turn39search2turn39search3turn23view4

### Modelos LLM candidatos para notas

| Modelo | Ventaja central | Riesgo central | Recomendación |
|---|---|---|---|
| Gemma 3n E2B-it | Diseñado para dispositivos de bajos recursos; API Android Kotlin oficial vía LiteRT-LM; foco on-device citeturn39search0turn23view4 | El rendimiento real en tu plantilla y español debe medirse | **Primer modelo a probar** |
| Qwen2.5 1.5B Instruct | Multilingüe amplio, contexto largo, buena reputación en tamaños pequeños citeturn39search1turn39search9 | Integración Android menos turnkey que Gemma+LiteRT-LM | **Alternativa principal** |
| Phi-4-mini-instruct | Contexto 128K y versiones LiteRT listas para Android existen citeturn39search2turn39search10 | Puede ser más pesado de lo deseable para ejecución sostenida local | **Alternativa si Gemma no alcanza calidad** |
| SmolLM3-3B | Buen rendimiento relativo en pequeño tamaño y multilingüe limitado citeturn39search3turn39search11 | 3B puede empezar a castigar latencia/termal en móvil | Interesante, no primera opción |
| Llama 3.x pequeños / GGUF | Ecosistema enorme y fácil de cuantizar con `llama.cpp` citeturn11search0 | Tamaños + integración + termal más exigentes | Mejor para etapa posterior |

### Selección recomendada del LLM

**Modelo inicial**: `google/gemma-3n-E2B-it` con LiteRT-LM.  
**Modelo alternativo**: `Qwen2.5-1.5B-Instruct` en GGUF/MLC o ruta equivalente.  
**Modelo de respaldo**: `Phi-4-mini-instruct` solo si el hardware y la latencia lo soportan.  

La razón para elegir Gemma 3n primero es brutalmente práctica: Google ya documenta LiteRT-LM para Android con API Kotlin, GPU/NPU acceleration y foco explícito en edge; Gemma 3n está diseñada para ejecución eficiente en dispositivos con recursos limitados y fue pensada para entornos tipo móvil. Si tu objetivo fuera solo calidad textual en abstracto, Qwen sería una tentación grande; pero tu objetivo no es un benchmark de oficina, es un MVP que funcione en tu Samsung sin convertirse en parrilla portátil. citeturn23view4turn10search8turn10search9turn39search0turn39search1

### Esquema de notas y método de generación

Propongo dos capas: una base general y plantillas específicas.

**Esquema base general**  
Título; fecha; duración; participantes; resumen; temas; decisiones; tareas pendientes; preguntas abiertas; eventos relevantes; fragmentos destacados; referencias temporales; etiquetas.

**Plantillas específicas**  
SOAP, DAP, BIRP y nota narrativa. El formato SOAP está muy consolidado en documentación clínica; BIRP organiza en Behavior, Intervention, Response, Plan. Tu producto no debe diagnosticar ni inferir salud; debe **estructurar lo dicho** y enlazarlo a la transcripción. citeturn15search4turn15search17

El pipeline de nota debe ser así:

```text
Transcripción final
  -> chunking por 5–10 minutos o por cambios temáticos
  -> extracción por bloque a JSON estructurado
  -> validación: cada item importante debe portar timestamp/evidencia
  -> fusión y deduplicación
  -> redacción final según plantilla
  -> editor humano
```

Eso permite además una UX poderosa: cada item importante de la nota puede tener un “ver evidencia” que salta al fragmento de transcripción correspondiente. Ese detalle vale oro, sobre todo en escenarios sensibles o profesionales, porque ataca el mayor pecado de muchos resumidores: sonar convincentes sin poder mostrar de dónde salió la frase. citeturn23view6turn23view7turn14search4turn14search5

### Persistencia y seguridad

Para datos estructurados, Room es la base correcta; Android lo documenta como capa sobre SQLite con acceso robusto y offline-friendly. Para configuración y flags pequeños, DataStore es preferible a SharedPreferences. Para cifrado de base local, SQLCipher sigue siendo la opción más clara y madura cuando necesitas cifrar la base completa. Para reautenticación local, BiometricPrompt es la API del sistema apropiada. La biblioteca `androidx.security:security-crypto` hoy muestra APIs deprecadas como `EncryptedSharedPreferences`, así que no conviene basar el diseño nuevo en esa ruta. citeturn43search1turn43search4turn43search2turn43search5turn41search2turn41search7turn42search1turn41search8turn41search21

### Modelo de datos recomendado

| Entidad | Campos clave |
|---|---|
| `Session` | `id`, `title`, `createdAt`, `startedAt`, `endedAt`, `status`, `durationMs`, `deviceModel`, `appVersion`, `audioPath`, `noteTemplateId` |
| `AudioSegment` | `id`, `sessionId`, `offsetStartMs`, `offsetEndMs`, `filePath`, `checksum`, `sampleRate`, `channels`, `status` |
| `TranscriptSegment` | `id`, `sessionId`, `seq`, `startMs`, `endMs`, `text`, `stableText`, `confidence?`, `speakerLabel?`, `isFinal`, `sourceModel` |
| `Note` | `id`, `sessionId`, `templateId`, `schemaVersion`, `contentMarkdown`, `contentJson`, `generatedAt`, `editedAt`, `generationModel` |
| `NoteTemplate` | `id`, `name`, `type`, `jsonSchema`, `promptConfig`, `isClinicalLike`, `version` |
| `Tag` | `id`, `name` |
| `SessionTagCrossRef` | `sessionId`, `tagId` |
| `PerformanceMetric` | `id`, `sessionId`, `timestamp`, `metricName`, `value`, `unit`, `phase`, `runtime`, `delegate` |
| `ModelConfiguration` | `id`, `modelType`, `modelName`, `quantization`, `runtime`, `delegate`, `language`, `paramsJson` |
| `ExportRecord` | `id`, `sessionId`, `format`, `createdAt`, `path`, `protected` |
| `ProcessingJob` | `id`, `sessionId`, `jobType`, `state`, `startedAt`, `endedAt`, `errorCode`, `attempts` |

Las sesiones deben ser **reanudables**. Si la app cae, la sesión no puede quedar como un fantasma triste del pasado. `Session.status` debe contemplar al menos: `NEW`, `RECORDING`, `PAUSED`, `RECOVERING`, `TRANSCRIBING`, `SUMMARIZING`, `COMPLETED`, `FAILED`, `ABORTED`. Room y sus migraciones cubren bien esta evolución; DataStore guarda toggles y configuración no relacional. citeturn43search1turn43search16turn43search2

### Privacidad, consentimiento y marco sensible

Tu diseño debe ser **local-first**, sin telemetría de contenido y con logs sin audio ni texto sensible por defecto. En Argentina, la Ley 25.326 exige consentimiento libre, expreso e informado para el tratamiento de datos personales, y la propia guía estatal de “ley simple” destaca que la protección alcanza datos de identidad, salud y otros datos sensibles. Eso no resuelve por sí solo toda la cuestión legal de grabación según contexto y jurisdicción, pero sí marca un piso de diseño: aviso claro, consentimiento verificable, retención configurable, exportación controlada y revisión jurídica específica si el producto sale de uso personal o entra en salud. citeturn16search0turn16search15turn15search11

## Roadmap operativo, pruebas y métricas de aceptación

El roadmap debe medirse por **capacidades validadas**, no por semanas. Cada fase deja una evidencia concreta: video, benchmark, trace, dataset, ADR o build reproducible.

### Fases recomendadas

| Fase | Propósito | Conocimientos a estudiar | Entregable y criterio de salida |
|---|---|---|---|
| Entorno e instrumentación | Proyecto base, CI, módulos, logging, profiling | Android Studio, Gradle, Compose, Hilt, Profiler, Perfetto, repositorio | App vacía modular con logging, profiling y build reproducible; ADRs iniciales citeturn18search16turn17search2turn18search1 |
| Captura fiable | Grabar largo tiempo con pantalla apagada y foreground service | Audio permissions, lifecycle, foreground services, `AudioRecord` | 90 min de grabación estable sin pérdida; pausa/reanuda; recuperación básica |
| Segmentación y VAD | Detectar voz/silencio y trocear sin cortar frases brutalmente | Frames, VAD, hangover, endpointing | CSV/JSON con segmentos y métricas; falsos cortes aceptables |
| ASR offline inicial | Transcribir archivos grabados ya terminados | JNI, `whisper.cpp`, cuantización, benchmark | Benchmark en español sobre corpus propio; comparación tiny/base/small |
| ASR incremental | Mostrar texto durante grabación con reconciliación estable | ventanas deslizantes, overlap, prefijo estable | UI en vivo con parciales/finales sin duplicaciones graves |
| Persistencia segura | Guardar sesiones, transcriptos y reanudación | Room, DataStore, SQLCipher, borrado, exportes | Historial, búsqueda simple, reanudación tras cierre forzado |
| Notas locales | Generar nota por bloques y consolidarla | LiteRT-LM o runtime elegido, prompts, JSON schemas | Nota estructurada editable con timestamps de evidencia |
| UX completa | Mejorar flujo, errores, estados, exportación | Compose state handling, UX de sesión larga | Flujo completo pulido de comienzo a fin |
| Optimización S25 | Medir CPU/GPU/NPU, termal, batería, memoria | Perfetto, Thermal API, Battery Historian, delegates | Informe comparativo Ultra vs Plus; selección definitiva de modelo/runtime |
| Validación final | Pruebas reales de 45/60/90 min y liberación | WER/CER, rúbrica de notas, criterios de release | Checklist de MVP cumplido y evidencia archivada |

### Contenido mínimo de cada fase

Cada fase debe registrar: objetivo; riesgos; decisiones tomadas; benchmark; build exacta; configuración del modelo; corpus usado; resultados p50/p95; captura de pantalla o video; y un ADR con lo que se elige y lo que se descarta. Ese archivo de evidencia es más valioso que cualquier épica en un tablero. Si dentro de tres semanas no recuerdas por qué descartaste un denoiser, el proyecto ya empezó a llenarse de fantasmas. citeturn18search0turn17search17turn18search1turn17search2

### Estrategia de pruebas

El corpus privado inicial debería empezar con **6 a 8 horas** bien distribuidas, no con veinte horas desordenadas. Propuesta práctica:

| Tipo | Minutos iniciales |
|---|---|
| Lectura con ground truth exacto | 60–90 |
| Conversaciones simuladas en ambiente controlado | 120 |
| Conversaciones espontáneas autorizadas | 120–180 |
| Ambientes ruidosos variados | 60–90 |
| Sesiones largas 45/60/90 min | 180 |
| Terminología profesional específica | 60 |

El ground truth debe ser humano, versionado, anonimizando nombres cuando corresponda y separando claramente datos de entrenamiento de datos de evaluación. La contaminación aquí sería veneno: si ajustas prompts o thresholds mirando exactamente el mismo set una y otra vez, te vas a contar una historia bonita mientras el producto falla en la vida real. citeturn43search1turn17search2

Debes medir al menos:

| Dimensión | Métrica |
|---|---|
| ASR | WER, CER, omisiones, inserciones, sustituciones, nombres propios, puntuación, timestamps, alucinaciones en silencio |
| Rendimiento | RTF, tiempo a primer texto, latencia media y p95, RAM pico, CPU, temperatura, thermal status, batería por hora |
| Robustez | llamadas, app al fondo, pantalla apagada, poco espacio, permiso revocado, cierre forzado, reinicio |
| Notas | fidelidad, cobertura, organización, utilidad, omisiones, distorsiones, trazabilidad a transcripción, editabilidad |

### Umbrales sugeridos para aceptación

Estos valores son **recomendaciones iniciales** y deben afinarse tras tu primer benchmark formal. No son leyes de la física; son barras de producto.

| Métrica | Mínimo aceptable | Objetivo deseado | Excelente | Bloquea release si… |
|---|---|---|---|---|
| Tiempo a primer texto parcial | ≤ 4 s | ≤ 2 s | ≤ 1 s | > 6 s sostenidos |
| Latencia incremental visible | ≤ 6 s | ≤ 3 s | ≤ 1.5 s | > 8 s o textos muy inestables |
| RTF ASR sostenido | ≤ 1.0 | ≤ 0.7 | ≤ 0.4 | > 1.2 en sesiones largas |
| WER corpus propio espontáneo | ≤ 22% | ≤ 15% | ≤ 10–12% | > 25% en ambientes objetivo |
| RAM pico total de inferencia | que no provoque LMK ni jank severo | estable y sin swapping perceptible | holgado en ambos equipos | el sistema mata el proceso |
| Batería por hora | aceptable para una sesión profesional | cómoda para 60 min | cómoda para 90 min | agota sesiones reales demasiado rápido |
| Temperatura / throttling | sin throttling severo prematuro | controlada 45–60 min | controlada 90 min | throttling fuerte antes de 20–30 min |
| Nota final | útil y editable | útil, fiel y bien estructurada | trazable y casi lista para usar | distorsiona contenido sensible |

### Registro de riesgos técnicos

| Riesgo | Probabilidad | Impacto | Señal temprana | Mitigación | Punto de decisión |
|---|---|---|---|---|---|
| ASR en español insuficiente | Media | Alta | WER alto en corpus propio | comparar Whisper base/small, mejorar chunking, corpus específico | fin de fase ASR inicial |
| Latencia excesiva | Media | Alta | p95 alto y UI inestable | bajar tamaño de modelo/chunk, usar GPU, reducir refinamientos | fase incremental |
| Sobrecalentamiento | Media | Alta | thermal headroom cae rápido | reducir concurrencia, CPU affinity no agresiva, procesar notas al final | optimización S25 |
| Batería excesiva | Media | Alta | drenaje/h > objetivo | apagar refinamientos en vivo, VAD más estricto, LLM solo post-sesión | optimización S25 |
| NPU inaccesible o inútil | Alta | Media | sin mejora real con QNN/LiteRT delegate | asumir CPU/GPU-path como principal | antes de dedicar tiempo a QNN |
| Diarización inviable | Alta | Media | memoria/latencia/precisión pobres | posponer; usar speaker labels manuales | desde el inicio |
| Corrupción de sesión | Baja-media | Alta | cierres en reanudación | writes atómicos, journaling, checksums por segmento | fase persistencia |
| Complejidad excesiva para un solo dev | Alta | Alta | fases sin cierre claro | recorte agresivo de alcance y ADRs obligatorios | siempre |
| Uso sensible indebido | Media | Alta | usuarios confían ciegamente en nota automática | disclaimers, editor humano obligatorio, trazabilidad | diseño de UX/seguridad |

## Conclusiones, decisiones inmediatas y criterios de fin de MVP

La respuesta corta, ahora sí sin humo, es esta.

### Respuestas explícitas a las preguntas críticas

| Pregunta | Respuesta |
|---|---|
| ¿Es técnicamente viable construir este MVP en Galaxy S25 Ultra y S25 Plus? | **Sí**, con alcance recortado y priorizando CPU/GPU como camino garantizable, no la NPU. citeturn23view0turn25view0turn30view0turn12search5 |
| ¿Qué parte puede ejecutarse completamente en tiempo real? | Captura, VAD, chunking y una **transcripción incremental por bloques cortos** sí pueden aspirar a tiempo real práctico. La palabra-por-palabra perfecta no debe prometerse en el primer corte. citeturn23view5turn12search4 |
| ¿Qué parte debería ejecutarse al finalizar la conversación? | Refinamiento final de transcripción, consolidación de segmentos, generación de notas estructuradas, exportación y cualquier diarización experimental. |
| ¿Qué stack exacto se recomienda para la primera implementación? | **Kotlin + Jetpack Compose + ViewModel/UDF + coroutines/Flow + Hilt + Foreground Service + AudioRecord + Room + DataStore + SQLCipher + BiometricPrompt + whisper.cpp por JNI + VAD WebRTC/Silero + Gemma 3n vía LiteRT-LM**. citeturn34search5turn33search10turn43search1turn43search2turn41search2turn42search1turn11search3turn23view4turn39search0 |
| ¿Qué modelo ASR debe probarse primero? | **Whisper base multilingüe cuantizado, ejecutado con whisper.cpp**. citeturn11search0turn11search3turn38search13 |
| ¿Qué modelo de lenguaje debe probarse primero? | **Gemma 3n E2B-it con LiteRT-LM**. citeturn23view4turn39search0 |
| ¿Qué capacidades deben excluirse del primer MVP? | Diarización robusta, backend de escritorio, integración con APIs privadas de Samsung/Galaxy AI, resumen en vivo, dependencia de nube, y cualquier promesa de NPU obligatoria. citeturn23view6turn23view2turn12search5 |
| ¿Cuál es el mayor riesgo técnico? | **La combinación de latencia/termal/calidad en ASR incremental local en español durante sesiones largas**. No es un único monstruo; es un monstruo de tres cabezas. |
| ¿Cuál debería ser el primer experimento de dos o tres días? | Construir un prototipo mínimo que: grabe con `AudioRecord`, corra VAD, haga chunking, y transcriba localmente un archivo/stream corto con `whisper.cpp` base y tiny en el S25 Ultra; medir tiempo a primer texto, RTF, RAM y temperatura. |
| ¿Qué evidencia objetiva determinará que el MVP está terminado? | Tres sesiones reales de 45, 60 y 90 minutos en S25 Ultra y S25+ sin pérdida de audio, con transcripción incremental utilizable, WER aceptable en corpus propio, nota final estructurada editable y trazable, exportación correcta, y benchmarks archivados de latencia, RAM, batería y termal. |

### Decisiones que debes tomar ya

Debes cerrar estas decisiones al arrancar:

| Decisión inmediata | Recomendación |
|---|---|
| Tecnología de app | Kotlin nativo + Compose |
| Runtime ASR inicial | whisper.cpp |
| VAD inicial | WebRTC primero por simplicidad; Silero como experimento paralelo |
| LLM de notas inicial | Gemma 3n + LiteRT-LM |
| Nivel de streaming del MVP | bloques cortos estabilizados, no palabra por palabra |
| Esquema de nota inicial | General + una plantilla profesional concreta |
| Persistencia | Room + DataStore + archivos locales cifrados |
| Escritorio | pospuesto |
| Denoising | desactivado por defecto hasta demostrar mejora |

### Decisiones que pueden esperar

Pueden aplazarse sin dolor: diarización, QNN/NPU real, modelo LLM alternativo, sincronización con escritorio, soporte amplio a otros Android, denoising avanzado, glosarios personalizados y edición semántica automatizada.

### Primer checklist operativo de liberación

| Ítem | Debe estar cumplido |
|---|---|
| Grabación 90 min con pantalla apagada | Sí |
| Pausa/reanuda y recuperación | Sí |
| Transcripción incremental visible | Sí |
| Timestamps confiables por segmento | Sí |
| Generación local de nota final | Sí |
| Edición de transcripción y nota | Sí |
| Búsqueda local por texto/fecha/título | Sí |
| Exportación Markdown/TXT | Sí |
| Cifrado local y biometría opcional | Sí |
| Benchmarks archivados Ultra/Plus | Sí |
| Sesiones reales 45/60/90 min aprobadas | Sí |
| ADRs y corpus versionados | Sí |

### Ventaja diferencial real del proyecto

No deberías intentar diferenciarte diciendo “transcribo mejor que Samsung” antes de tener pruebas. La diferenciación sólida está en otro sitio: **privacidad local**, **sesiones largas presenciales**, **control total del usuario**, **trazabilidad entre nota y transcripción**, **plantillas profesionales editables**, y **optimización específica para español latinoamericano y uso real offline**. Samsung Voice Recorder y Galaxy AI ya ofrecen transcripción, resumen y traducción al usuario final; Google Recorder ofrece transcripción on-device y búsqueda, aunque algunas re-transcripciones pueden usar servidores; Otter y Fireflies dominan el terreno cloud y de reuniones online con planes pagos; Plaud resuelve la captura presencial con hardware dedicado. Tu hueco está entre todos ellos: software local, privado, largo, estructurado y bajo control. citeturn23view6turn23view7turn14search1turn14search2turn14search4turn14search5turn14search11

La conclusión final es simple y útil: **construye primero una máquina confiable de capturar, segmentar y transcribir localmente**. Si esa máquina respira bien durante 90 minutos, el resumen estructurado viene después y encima con menos drama. Si esa máquina no respira, ningún “agente inteligente” la va a salvar. Aquí, como en clínica y en ingeniería, primero se estabiliza al paciente. Luego se embellece la nota.