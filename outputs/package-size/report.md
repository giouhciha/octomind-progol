# Reducción del paquete: aporte por boleto

El paquete se genera ordenado (primero las composiciones más probables). Cada fila juega solo los primeros N boletos y reporta el mejor acierto medio. No usa datos futuros: el paquete se arma antes del concurso evaluado.

Se evaluó uno de cada 5 concursos. Configuración: `prior_20_div_20`.

## MS · 9 partidos

Histórico: 814 concursos.

| Boletos | Mejor acierto (desarrollo) | Mejor acierto (prueba final) | Ganancia marginal vs anterior | Cerca del pleno en prueba |
|---:|---:|---:|---:|---:|
| 1 | 3.18 | 3.21 | — | 0.00% |
| 2 | 4.10 | 4.05 | +0.837 | 0.00% |
| 3 | 4.51 | 4.63 | +0.581 | 0.00% |
| 5 | 4.81 | 4.91 | +0.279 | 0.00% |
| 8 | 5.24 | 5.09 | +0.186 | 0.00% |
| 10 | 5.44 | 5.33 | +0.233 | 2.33% |
| 15 | 5.77 | 5.70 | +0.372 | 4.65% |
| 20 | 5.96 | 5.86 | +0.163 | 4.65% |

## WEEKEND · 14 partidos

Histórico: 1577 concursos.

| Boletos | Mejor acierto (desarrollo) | Mejor acierto (prueba final) | Ganancia marginal vs anterior | Cerca del pleno en prueba |
|---:|---:|---:|---:|---:|
| 1 | 4.75 | 4.71 | — | 0.00% |
| 2 | 5.98 | 6.06 | +1.348 | 0.00% |
| 3 | 6.57 | 6.56 | +0.506 | 0.00% |
| 5 | 7.09 | 7.07 | +0.506 | 0.00% |
| 8 | 7.58 | 7.69 | +0.618 | 0.00% |
| 10 | 7.79 | 7.71 | +0.023 | 0.00% |
| 15 | 8.08 | 7.94 | +0.236 | 0.00% |
| 20 | 8.32 | 8.17 | +0.225 | 0.00% |

## REVANCHA · 7 partidos

Histórico: 931 concursos.

| Boletos | Mejor acierto (desarrollo) | Mejor acierto (prueba final) | Ganancia marginal vs anterior | Cerca del pleno en prueba |
|---:|---:|---:|---:|---:|
| 1 | 2.47 | 2.58 | — | 0.00% |
| 2 | 3.44 | 3.46 | +0.880 | 0.00% |
| 3 | 3.79 | 3.78 | +0.320 | 0.00% |
| 5 | 4.22 | 4.00 | +0.220 | 0.00% |
| 8 | 4.52 | 4.34 | +0.340 | 2.00% |
| 10 | 4.67 | 4.38 | +0.040 | 2.00% |
| 15 | 4.81 | 4.64 | +0.260 | 8.00% |
| 20 | 4.99 | 4.86 | +0.220 | 10.00% |

La ganancia marginal muestra cuánto aporta cada recorte adicional. Si cae por debajo del costo de un boleto, jugar ese boleto extra deja de ser eficiente.
