# Motor de análisis — versiones y rollback

Este documento registra las dos versiones del motor de pronóstico para poder
hacer rollback de forma exacta. El "motor" principal de la app es
`app/src/main/java/mx/octomind/progol/PredictionEngine.java`; su referencia
experimental en Python es `src/progol_media_semana/engine.py`.

---

## Motor v1 (antes de los cambios del 2026-10-05)

**Referencia git:** tag `motor-v1` → commit `a038b85` (Release Android app v0.4.7).

Recuperar el motor v1 completo desde el tag:

```powershell
git checkout motor-v1 -- app/src/main/java/mx/octomind/progol/PredictionEngine.java app/src/main/java/mx/octomind/progol/MainActivity.java app/src/main/res/values/strings.xml
```

### Definición

- **Modelo de casilla:** frecuencias globales suavizadas (Laplace +1), idénticas
  para las 9 casillas. `MODEL_VERSION = "media-semana-baseline-1"`.
- **Paquete:** `DEFAULT_PACKAGE_SIZE = 20` combinaciones.
- **Composición:** prior multinomial suavizado `COMPOSITION_PRIOR_STRENGTH = 20.0`.
- **Diversidad:** `DIVERSITY_WEIGHT = 0.20` (distancia de Hamming).
- **Selección golosa:** maximiza `log(probabilidad) + 0.20·distanciaMínima`;
  desempate por menor racha máxima y luego secuencia lexicográficamente menor.
- **Generación de candidatos:**
  - MS y Revancha (≤9 casillas): enumeración exhaustiva del universo.
  - Fin de Semana (14): *reservoir sampling* con `java.util.Random(731L)` y
    `maxPerComposition = 1500`.
- **Favoritos:** solo *fijar* resultados (`analyze(history, fixedStates)`),
  límite 2 (MS) / 4 (WEEKEND) / 1 (REVANCHA). Sin descartes.
- **Sin** promedio esperado por boleto ni ordenamiento por promedio.

---

## Motor v2 (cambios del 2026-10-05)

**Estado:** árbol de trabajo actual (aún sin commit). Versión de app `0.4.10`.

Cuando se haga commit, etiquetarlo como `motor-v2`:

```powershell
git commit -am "Motor v2: favoritos + descartes, paquete 10, muestreo determinista, promedio esperado"
git tag motor-v2
```

### Cambios respecto a v1

1. **Paquete de 10 combinaciones** (`PACKAGE_SIZE = 10`, pública).
2. **Muestreo determinista para 14 casillas:** `SplitMix64` con semilla derivada
   de la composición; si las permutaciones caben en el cupo se enumeran, si no se
   muestrean sin reemplazo. Elimina `java.util.Random`.
3. **Promedio esperado por boleto:** `Forecast.expectedHits(sequence)` y la vista
   ordena los boletos de mayor a menor promedio esperado.
4. **Favoritos + descartes:** `analyze(history, fixedStates, discardedStates)`.
   Fijar (L/E/V) y prohibir (✕L/✕E/✕V) por casilla. `availableCount` por
   programación dinámica para respetar descartes en los cupos de composición.
5. **UI de favoritos** con 7 opciones por casilla en `MainActivity`.

### Archivos del motor v2 (diferentes a v1)

- `app/src/main/java/mx/octomind/progol/PredictionEngine.java`
- `app/src/main/java/mx/octomind/progol/MainActivity.java`
- `app/src/main/res/values/strings.xml`
- `app/src/test/java/mx/octomind/progol/MultiDrawTest.java`
- `app/src/test/java/mx/octomind/progol/PredictionEngineTest.java`

---

## Nota

El motor Python (`src/progol_media_semana/engine.py`) **no cambió** entre v1 y v2;
sigue siendo la referencia experimental de Media Semana. Los scripts
(`scripts/run_package_backtest.py`, `run_model_significance.py`,
`run_package_size_backtest.py`) son herramientas de análisis, no el motor de la app.
