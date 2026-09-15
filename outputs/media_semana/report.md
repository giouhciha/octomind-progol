# Experimento Progol Media Semana

Fuente: https://www.loterianacional.gob.mx/Documentos/Historicos/Progol-Media-S.csv

## Auditoria del historico

- Concursos: 811
- Periodo: 2010-04-01 a 2026-09-11
- Concursos faltantes en la numeracion: 490
- Concursos duplicados: ninguno
- Estados: L=3155, E=1954, V=2190

## Metodo de evaluacion

Los primeros 100 concursos forman el historial minimo. Cada prediccion posterior usa solo concursos ya cerrados. El modelo se elige en desarrollo hasta el concurso 598 y se informa su rendimiento en el tramo final reservado.

## Comparacion en desarrollo

| Modelo | Log-loss | Brier | Acierto principal | Cobertura top 2 |
|---|---:|---:|---:|---:|
| global_frequency | 1.0846 | 0.6572 | 41.92% | 72.57% |
| recent_hl_104 | 1.0853 | 0.6571 | 41.92% | 70.85% |
| recent_hl_52 | 1.0856 | 0.6572 | 41.87% | 70.65% |
| blend_hl_52 | 1.0862 | 0.6577 | 41.81% | 70.71% |
| blend_hl_104 | 1.0863 | 0.6578 | 41.92% | 71.14% |
| blend_hl_26 | 1.0867 | 0.6580 | 41.83% | 70.98% |
| recent_hl_26 | 1.0869 | 0.6580 | 41.65% | 71.00% |
| position_frequency | 1.0872 | 0.6587 | 41.92% | 71.79% |
| recent_hl_13 | 1.0881 | 0.6589 | 41.69% | 71.32% |
| transition | 1.0935 | 0.6626 | 41.87% | 71.14% |
| uniform | 1.0986 | 0.6667 | 41.92% | 69.33% |

## Prueba final reservada

| Modelo | Log-loss | Brier | Acierto principal | Cobertura top 2 |
|---|---:|---:|---:|---:|
| position_frequency | 1.0755 | 0.6509 | 42.99% | 74.20% |
| blend_hl_104 | 1.0760 | 0.6513 | 42.78% | 74.61% |
| transition | 1.0766 | 0.6516 | 43.04% | 74.14% |
| blend_hl_52 | 1.0768 | 0.6519 | 42.94% | 74.56% |
| recent_hl_104 | 1.0768 | 0.6519 | 42.83% | 74.56% |
| global_frequency | 1.0770 | 0.6519 | 42.99% | 73.73% |
| blend_hl_26 | 1.0774 | 0.6522 | 43.20% | 73.78% |
| recent_hl_52 | 1.0783 | 0.6529 | 42.37% | 74.20% |
| recent_hl_13 | 1.0793 | 0.6534 | 43.04% | 73.36% |
| recent_hl_26 | 1.0795 | 0.6536 | 42.68% | 73.99% |
| uniform | 1.0986 | 0.6667 | 42.99% | 69.26% |

## Modelo seleccionado

El modelo elegido en desarrollo es `global_frequency`. En la prueba final iguala el log-loss de la frecuencia global por 0.0000.

## Evaluacion de combinaciones completas

| Tramo | Modelo | Log-loss por quiniela | Equivalente por casilla |
|---|---|---:|---:|
| Desarrollo | global_independent | 9.7611 | 1.0846 |
| Desarrollo | global_composition | 9.8607 | 1.0956 |
| Prueba final | global_independent | 9.6934 | 1.0770 |
| Prueba final | global_composition | 9.7351 | 1.0817 |

La composicion historica no mejoro la probabilidad predictiva conjunta en la prueba final; aumento el log-loss por quiniela en 0.0417. Por eso se usa para repartir un paquete de combinaciones plausibles, no como una supuesta certeza adicional.

## Pronostico experimental del concurso 813

| Casilla | L | E | V | Principal | Segunda | Incertidumbre |
|---:|---:|---:|---:|:---:|:---:|---:|
| 1 | 43.22% | 26.77% | 30.01% | L | V | 97.99% |
| 2 | 43.22% | 26.77% | 30.01% | L | V | 97.99% |
| 3 | 43.22% | 26.77% | 30.01% | L | V | 97.99% |
| 4 | 43.22% | 26.77% | 30.01% | L | V | 97.99% |
| 5 | 43.22% | 26.77% | 30.01% | L | V | 97.99% |
| 6 | 43.22% | 26.77% | 30.01% | L | V | 97.99% |
| 7 | 43.22% | 26.77% | 30.01% | L | V | 97.99% |
| 8 | 43.22% | 26.77% | 30.01% | L | V | 97.99% |
| 9 | 43.22% | 26.77% | 30.01% | L | V | 97.99% |

## Paquete de combinaciones recomendado

El universo completo contiene 19,683 quinielas sencillas. La seleccion asigna cupos segun la composicion historica y despues maximiza probabilidad base y diversidad.
Cuando varias secuencias tienen el mismo peso, su orden se decide por diversidad; no se interpreta como una ventaja predictiva adicional.

| Orden | Combinacion | L | E | V | Peso composicion | Prob. base exacta | Distancia |
|---:|:---:|---:|---:|---:|---:|---:|---:|
| 1 | VLVLVLELE | 4 | 2 | 3 | 8.75% | 0.0068% | 0 |
| 2 | LVLVLEVEL | 4 | 2 | 3 | 8.75% | 0.0068% | 9 |
| 3 | LLELEVLVL | 5 | 2 | 2 | 7.88% | 0.0097% | 7 |
| 4 | VLLELELLV | 5 | 2 | 2 | 7.88% | 0.0097% | 6 |
| 5 | VELVELEVL | 3 | 3 | 3 | 7.03% | 0.0042% | 6 |
| 6 | LEVLVELEV | 3 | 3 | 3 | 7.03% | 0.0042% | 6 |
| 7 | LEVELVELL | 4 | 3 | 2 | 6.44% | 0.0060% | 6 |
| 8 | LVELVLVLV | 4 | 1 | 4 | 5.41% | 0.0076% | 5 |
| 9 | EVVEVLLVL | 3 | 2 | 4 | 5.32% | 0.0047% | 6 |
| 10 | ELVVELVEV | 2 | 3 | 4 | 4.90% | 0.0029% | 6 |
| 11 | LVLLELLEE | 5 | 3 | 1 | 4.68% | 0.0087% | 5 |
| 12 | EVLLLVLLL | 6 | 1 | 2 | 4.31% | 0.0157% | 5 |
| 13 | LELEVEVLE | 3 | 4 | 2 | 3.84% | 0.0037% | 5 |
| 14 | VLEELLEEL | 4 | 4 | 1 | 3.57% | 0.0054% | 5 |
| 15 | LLVVLLLVE | 5 | 1 | 3 | 3.14% | 0.0109% | 5 |
| 16 | LLELLEVLL | 6 | 2 | 1 | 2.73% | 0.0140% | 4 |
| 17 | LVEVEVELE | 2 | 4 | 3 | 2.61% | 0.0026% | 5 |
| 18 | VLLLVVVEV | 3 | 1 | 5 | 2.47% | 0.0053% | 5 |
| 19 | VLLVLLVLL | 6 | 0 | 3 | 2.20% | 0.0176% | 4 |
| 20 | VVLVVELVE | 2 | 2 | 5 | 2.10% | 0.0033% | 5 |

Este pronostico es una estimacion experimental basada solo en la secuencia historica L/E/V. No incorpora los equipos del siguiente concurso y no implica certeza ni garantia de premio.
