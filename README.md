# Octomind Progol

Aplicacion Android nativa y motor reproducible para estimar las probabilidades
`L`, `E` y `V` de las nueve casillas del siguiente concurso de Progol Media
Semana.

## Version Android (0.3.2)

- Descarga y valida el historico oficial de Progol Media Semana.
- Guarda concursos y pronosticos localmente en SQLite para trabajar sin conexion.
- Calcula las 19,683 secuencias posibles y entrega un paquete diverso de 20.
- Presenta cada pronostico como una pagina visual de nueve partidos: la opcion
  L/E/V elegida se resalta en verde y se cambia deslizando horizontalmente.
- Mantiene las probabilidades base como parte interna del calculo, sin mostrar
  la tabla repetitiva de probabilidades por casilla en la interfaz.
- Unifica controles, estados seleccionados, fondos y elementos del sistema con
  la paleta verde usada por las combinaciones.
- Reparte el paquete entre composiciones L/E/V observadas historicamente; no
  interpreta que la seleccion independiente de mayor probabilidad deba jugarse
  como nueve locales.
- Crea y restaura respaldos JSON mediante el selector de documentos de Android.
  Desde ahi puede elegirse Google Drive si esta configurado en el dispositivo.
- Conserva los 20 pronosticos por concurso en una pantalla de seguimiento.
- Permite registrar resultados parciales por casilla, sin desplazar posiciones.
- Ordena la comparacion de mayor a menor cantidad de aciertos.
- Cuando el concurso aparece en el historico oficial, sustituye la captura manual
  por el resultado validado y definitivo.

El APK instalable de prueba se genera en
`app/build/outputs/apk/debug/app-debug.apk`.

## Criterio del modelo

- Usa exclusivamente el historico oficial de Progol Media Semana.
- Valida el CSV antes de analizarlo.
- Evalua los modelos de forma cronologica: cada concurso solo puede usar los
  resultados publicados anteriormente.
- Compara cada candidato contra referencias simples.
- Genera un reporte y un pronostico probabilistico para el siguiente concurso.

La version actual usa como base las frecuencias globales validadas por backtest.
Las frecuencias por casilla no superaron de forma consistente esa referencia,
por lo que no se fuerza una diferencia artificial entre posiciones. La capa de
combinaciones conserva del sistema de referencia la idea util de restricciones,
composicion y diversidad, pero no su interfaz ni sus parametros arbitrarios.

El resultado es una estimacion experimental, no una garantia de premio.

## Compilar la aplicacion

Abra la carpeta en Android Studio y ejecute la configuracion `app`, o use:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

Requiere Android SDK 36 y Java 17 o posterior.

## Experimento reproducible

## Ejecutar el experimento

Se requiere Python 3.11 o posterior y acceso a Internet:

```powershell
python scripts/run_media_semana_experiment.py
```

Los resultados se guardan en `outputs/media_semana/`:

- `report.md`: auditoria, comparacion de modelos y pronostico.
- `experiment.json`: resultados estructurados para futuras integraciones.

Para analizar una copia local del CSV:

```powershell
python scripts/run_media_semana_experiment.py --input ruta/al/archivo.csv
```

## Pruebas

```powershell
python -m unittest discover -s tests -v
```

## Fuente

[Historico oficial de Progol Media Semana](https://www.loterianacional.gob.mx/Documentos/Historicos/Progol-Media-S.csv)
