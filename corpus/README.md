# Corpus ASR local

El corpus real vive en `corpus/private/` y no se versiona porque puede contener
voz y texto sensible. El repositorio conserva solamente el esquema, herramientas y
resultados técnicos sanitizados.

## Estructura

Copiar `manifest.example.json` como `corpus/private/manifest.json` y crear:

```text
corpus/private/
  manifest.json
  references/<item-id>.txt
  vad-references/<item-id>.json
  results/<item-id>--<capture-pipeline>--<model-id>.json
  vad-timelines/<item-id>--<engine>--<capture-pipeline>.json
```

Cada resultado es el `asr-result-*.json` producido por la APK, renombrado para
asociarlo al item. Todos los modelos deben usar exactamente el mismo audio/VAD.

El conjunto inicial debe incluir lectura, conversación espontánea, ruido y silencio;
además conviene marcar nombres propios. No incorporar grabaciones sin consentimiento
expreso ni subir audio, referencias o transcripciones reales al repositorio.

## Evaluación

```powershell
python .\tools\evaluate_asr.py `
  --manifest .\corpus\private\manifest.json `
  --results-dir .\corpus\private\results `
  --output-dir .\artifacts\private\asr-evaluation
```

La salida incluye JSON y CSV con WER/CER, sustituciones, omisiones, inserciones,
RTF y detección de texto alucinado en items de silencio.

Para evaluar WebRTC y Silero contra los intervalos anotados por una persona:

```powershell
python .\tools\evaluate_vad.py `
  --manifest .\corpus\private\manifest.json `
  --timelines-dir .\corpus\private\vad-timelines `
  --output-dir .\artifacts\private\vad-evaluation
```

Cada referencia VAD sigue `vad-reference.example.json`. La salida JSON/CSV informa
precisión, recall y F1 por tiempo, habla perdida, falsos positivos, error de bordes
y fragmentación, agrupados por motor y pipeline de captura.
