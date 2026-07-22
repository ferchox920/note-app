# Protocolo reproducible: comparación ASR tiny/base/small

## Objetivo

Comparar los tres modelos multilingües `q5_1` sobre exactamente el mismo corpus
español autorizado y producir WER/CER, errores desglosados, alucinaciones en
silencio y métricas de rendimiento.

## Preparación del corpus

1. Copiar `corpus/manifest.example.json` a `corpus/private/manifest.json`.
2. Confirmar consentimiento en el manifiesto solamente después de obtenerlo.
3. Completar lectura, conversación, habla con ruido y silencio, con referencias
   literales UTF-8 revisadas por una persona.
4. Mantener audio, referencias y transcripciones bajo `corpus/private/`.

## Inferencia

1. Ejecutar `tools/download-whisper-models.ps1 -Model all`.
2. Importar tiny, base y small en la APK.
3. Para cada item, ejecutar los tres modelos sobre la misma sesión finalizada.
4. Copiar cada JSON a `corpus/private/results/` con nombre
   `<item-id>--<capture-pipeline>--<model-id>.json`.

## Reporte

```powershell
python .\tools\evaluate_asr.py `
  --manifest .\corpus\private\manifest.json `
  --results-dir .\corpus\private\results `
  --output-dir .\artifacts\private\asr-evaluation
```

Revisar `asr-evaluation.json` y `.csv`. La elección de modelo exige, además del
promedio, revisar conversación espontánea, nombres propios, omisiones y cada item
de silencio. El umbral inicial es WER espontáneo <= 25%; si no se alcanza debe
quedar un plan de mitigación aprobado antes de avanzar.

No aprobar el ADR de modelo sin resultados del S25 y sin registrar RTF, memoria y
estado térmico junto con calidad.
