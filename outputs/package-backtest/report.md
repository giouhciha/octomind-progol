# Backtest de paquetes de 20 combinaciones

No usa datos futuros: cada paquete se genera antes del concurso evaluado.
Se evaluó cada 5 concurso para mantener el análisis reproducible y manejable.

## MS · 9 partidos

Histórico: 811 concursos. Parámetro elegido en desarrollo: `prior_40_div_20`.

| Configuración | Mejor acierto medio (desarrollo) | Mejor acierto medio (prueba final) | Cerca del pleno en prueba final |
|---|---:|---:|---:|
| prior_0_div_0 | 5.49 | 5.44 | 2.33% |
| prior_10_div_0 | 5.48 | 5.44 | 2.33% |
| prior_20_div_0 | 5.48 | 5.44 | 2.33% |
| prior_20_div_20 | 6.00 | 6.09 | 4.65% |
| prior_20_div_50 | 6.00 | 6.09 | 4.65% |
| prior_40_div_20 | 6.00 | 6.09 | 4.65% |

## WEEKEND · 14 partidos

Histórico: 1575 concursos. Parámetro elegido en desarrollo: `prior_40_div_20`.

| Configuración | Mejor acierto medio (desarrollo) | Mejor acierto medio (prueba final) | Cerca del pleno en prueba final |
|---|---:|---:|---:|
| prior_0_div_0 | 8.06 | 7.80 | 0.00% |
| prior_10_div_0 | 8.06 | 7.80 | 0.00% |
| prior_20_div_0 | 8.06 | 7.80 | 0.00% |
| prior_20_div_20 | 8.30 | 8.20 | 0.00% |
| prior_20_div_50 | 8.30 | 8.20 | 0.00% |
| prior_40_div_20 | 8.31 | 8.20 | 0.00% |

## REVANCHA · 7 partidos

Histórico: 929 concursos. Parámetro elegido en desarrollo: `prior_20_div_20`.

| Configuración | Mejor acierto medio (desarrollo) | Mejor acierto medio (prueba final) | Cerca del pleno en prueba final |
|---|---:|---:|---:|
| prior_0_div_0 | 4.76 | 4.68 | 18.00% |
| prior_10_div_0 | 4.76 | 4.68 | 18.00% |
| prior_20_div_0 | 4.76 | 4.68 | 18.00% |
| prior_20_div_20 | 4.99 | 4.86 | 10.00% |
| prior_20_div_50 | 4.99 | 4.86 | 10.00% |
| prior_40_div_20 | 4.98 | 4.86 | 10.00% |

La configuración se considera adoptable solo si mejora también el tramo final reservado; de lo contrario se conserva la actual `prior_20_div_20`.
