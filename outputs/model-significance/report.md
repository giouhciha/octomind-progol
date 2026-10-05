# Prueba de significancia del motor

Fuente: https://www.loterianacional.gob.mx/Documentos/Historicos/Progol-Media-S.csv

Evaluacion cronologica (walk-forward). Se reservan 215 concursos finales como prueba. Cada modelo se compara contra `global_frequency` con un bootstrap pareado por concurso; el intervalo de confianza al 95% que cruza el cero indica que la diferencia no es concluyente.

| Modelo | Log-loss prueba | Diferencia vs global | IC 95% | p-valor |
|---|---:|---:|---:|---:|
| global_frequency | 9.6930 | +0.0000 | [+0.0000, +0.0000] | 1.000 |
| position_frequency | 9.6796 | -0.0134 | [-0.0361, +0.0103] | 0.273 |
| transition | 9.6898 | -0.0032 | [-0.0390, +0.0328] | 0.897 |
| recent_hl_104 | 9.6929 | -0.0001 | [-0.0383, +0.0382] | 0.991 |
| blend_hl_104 | 9.6852 | -0.0078 | [-0.0412, +0.0268] | 0.653 |

| Dependencia entre casillas | Log-loss prueba | Diferencia | IC 95% | p-valor |
|---|---:|---:|---:|---:|
| global_composition vs global_independent | 9.7317 | +0.0387 | [-0.0075, +0.0892] | 0.121 |

Ninguna variante supera a la frecuencia global de forma estadisticamente significativa en la prueba final. Por eso el motor conserva la frecuencia global suavizada y no incorpora senales por casilla, recencia ni dependencia entre partidos.
