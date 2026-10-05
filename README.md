# Octomind Progol

Aplicacion Android nativa y motor reproducible para estimar las probabilidades
`L`, `E` y `V` del siguiente concurso de Mitad de Semana (9 partidos),
Fin de Semana (14) y Revancha (7).

## Version Android (0.4.10)

- Descarga y valida el historico oficial de cada uno de los tres sorteos.
- Selector entre Mitad de Semana y Fin de Semana + Revancha. En esta ultima
  vista cada pagina muestra los 14 partidos y debajo los 7 de Revancha.
- Cada sorteo tiene su motor configurado, historial, paquetes y captura separados.
  Principal y Revancha comparten el codigo oficial 10, pero no sus datos.
- Si los ultimos concursos de principal y Revancha no coinciden, se informa y
  no se presentan como un paquete conjunto de concursos diferentes.
- Guarda concursos y pronosticos localmente en SQLite para trabajar sin conexion.
- Entrega 10 combinaciones por sorteo. MS enumera 19,683 secuencias, Revancha
  2,187 y Principal 4,782,969. Para Principal se conserva una muestra
  reproducible de hasta 1,500 candidatos por composicion seleccionada;
  esta seleccion aproximada limita la memoria del dispositivo.
- Al calcular con favoritos guarda dos grupos independientes de 10: uno
  automático y uno personalizado. Ambos se conservan en el respaldo y pueden
  compararse por separado en Seguimiento.
- Permite, ademas de fijar resultados, prohibir resultados por casilla
  (descartes ✕L/✕E/✕V). El paquete respeta tanto los fijos como los
  descartes, recondicionando la composicion.
- Presenta cada pronostico como una pagina visual de nueve partidos: la opcion
  L/E/V elegida se resalta en verde y se cambia deslizando horizontalmente.
- Ordena las combinaciones por su promedio esperado de aciertos (de mayor a
  menor), calculado con las probabilidades del modelo. Este orden es solo de
  presentacion: no altera el paquete generado, su respaldo ni el orden que usa
  el analisis de reduccion.
- Mantiene las probabilidades base como parte interna del calculo, sin mostrar
  la tabla repetitiva de probabilidades por casilla en la interfaz.
- Unifica controles, estados seleccionados, fondos y elementos del sistema con
  la paleta verde usada por las combinaciones.
- Reparte el paquete entre composiciones L/E/V observadas historicamente; no
  interpreta que la seleccion independiente de mayor probabilidad deba jugarse
  como nueve locales.
- Crea y restaura respaldos JSON mediante el selector de documentos de Android.
  Desde ahi puede elegirse Google Drive si esta configurado en el dispositivo.
- Conserva los 10 pronosticos por concurso en una pantalla de seguimiento.
- Permite registrar resultados parciales por casilla, sin desplazar posiciones.
- Ordena la comparacion de mayor a menor cantidad de aciertos.
- Cuando el concurso aparece en el historico oficial, sustituye la captura manual
  por el resultado validado y definitivo.
- El primer paquete guardado de cada concurso se conserva al recalcular.
- El respaldo conjunto incluye los tres sorteos. Los respaldos antiguos de
  Mitad de Semana siguen siendo compatibles y no sustituyen los otros sorteos.

El APK instalable de prueba se genera en
`app/build/outputs/apk/debug/app-debug.apk`.

## Criterio del modelo

- Cada motor usa exclusivamente el historico oficial de su propio sorteo.
- Valida el CSV antes de analizarlo.
- Evalua los modelos de forma cronologica: cada concurso solo puede usar los
  resultados publicados anteriormente.
- Compara cada candidato contra referencias simples.
- Genera un reporte y un pronostico probabilistico para el siguiente concurso.

La version actual usa frecuencias globales suavizadas por sorteo. El backtest
existente corresponde a Mitad de Semana; sus conclusiones no se trasladan
como evidencia de rendimiento a Principal o Revancha.
Las frecuencias por casilla no superaron de forma consistente esa referencia,
por lo que no se fuerza una diferencia artificial entre posiciones. La capa de
combinaciones conserva del sistema de referencia la idea util de restricciones,
composicion y diversidad, pero no su interfaz ni sus parametros arbitrarios.

Una prueba de significancia con bootstrap pareado (ver
`scripts/run_model_significance.py`) confirma que ninguna variante del modelo
(frecuencia por casilla, recencia, transicion o dependencia entre partidos)
supera a la frecuencia global de forma concluyente en la prueba final: todos
los intervalos de confianza al 95 % cruzan el cero. Por eso el motor conserva
el modelo actual y no incorpora señales adicionales.

El resultado es una estimacion experimental, no una garantia de premio.

## Compilar la aplicacion

Abra la carpeta en Android Studio y ejecute la configuracion `app`, o use:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

Requiere Android SDK 36 y Java 17 o posterior.

## Experimento reproducible

### Backtest del paquete de 20 combinaciones

El siguiente análisis evalúa el mejor boleto de cada paquete de 20 de forma
cronológica para Mitad de Semana, Fin de Semana y Revancha. Separa desarrollo
y prueba final, por lo que una configuración solo se adopta si también se
mantiene fuera del tramo donde se eligió.

```powershell
& 'C:\Program Files\Amazon\AWSSAMCLI\runtime\python.exe' scripts/run_package_backtest.py --step 50
```

El reporte se guarda en `outputs/package-backtest/report.md`. El valor
actual del motor (`prior_20_div_20`) se conserva como referencia: no se
reemplaza por una variante que no supere la prueba final.

### Reducción del paquete de combinaciones

Mide cuánto aporta cada boleto: genera el paquete ordenado y evalúa el mejor
acierto medio cuando se juegan solo los primeros 1, 2, 3, 5, 8, 10, 15 o 20.
Descarga los históricos de los tres sorteos a `app/build/qa-assets/` si no
existen (el mismo directorio que usa `run_package_backtest.py`):

```powershell
python scripts/run_package_size_backtest.py
```

El reporte se guarda en `outputs/package-size/report.md`. Con el histórico
actual las primeras 2 o 3 combinaciones capturan la mayor parte de la ganancia;
después de unos 10 boletos cada uno aporta poco al acierto medio, aunque los
boletos extra sí aumentan la probabilidad de acercarse al pleno (sobre todo en
Revancha). Reducir a 5-10 boletos es razonable si se busca el acierto medio;
mantener 20 se justifica para perseguir el pleno.

### Prueba de significancia del modelo

Comprueba si alguna variante del motor supera a la frecuencia global de forma
estadísticamente concluyente. Evalúa cada modelo en orden cronológico, separa
desarrollo y prueba final y aplica un bootstrap pareado por concurso:

```powershell
python scripts/run_model_significance.py
```

El reporte se guarda en `outputs/model-significance/report.md`. Con el histórico
actual ninguna variante (frecuencia por casilla, recencia, transición o
dependencia entre partidos) supera a la frecuencia global: todos los intervalos
de confianza al 95 % cruzan el cero. Por eso el motor conserva el modelo actual.

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

Las pruebas Android del motor incluyen aislamiento por sorteo, esquemas CSV,
10 combinaciones únicas y reproducibles, composiciones extremas y aciertos
parciales para 7 y 14 posiciones:

```powershell
.\gradlew.bat testDebugUnitTest
```

`IntegrationInstrumentation` verifica SQLite, respaldos y conciliación oficial
en bases temporales con prefijo `qa_`. Por defecto descarga los históricos.
Para una prueba sin red admite `-e useFixtures true` usando archivos oficiales
`MS.csv`, `WEEKEND.csv` y `REVANCHA.csv` en `app/build/qa-assets/` antes de
compilar el APK de pruebas. Esos archivos no se distribuyen en la aplicación.
La opción `-e seedUi true` es exclusiva de un emulador desechable: carga
los datos oficiales en la app de esa instancia para verificar su interfaz.

```powershell
python -m unittest discover -s tests -v
```

## Fuente

[Historico oficial de Progol Media Semana](https://www.loterianacional.gob.mx/Documentos/Historicos/Progol-Media-S.csv)

[Histórico de Fin de Semana](https://www.loterianacional.gob.mx/Home/Historicos?ARHP=UAByAG8AZwBvAGwA)

[Histórico de Revancha](https://www.loterianacional.gob.mx/Home/Historicos?ARHP=UAByAG8AZwBvAGwALQBSAGUAdgBhAG4AYwBoAGEA)
