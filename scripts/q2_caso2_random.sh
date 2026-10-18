#!/usr/bin/env bash
# Q2 - Caso 2 (variante): política RANDOM no diretório (-Dpolitica=random), 10 chamadas
source "$(dirname "$0")/_lib.sh"; compilar; limpar_logs
titulo "Q2 CASO 2 (variante) - balanceamento RANDOM"
bg diretorio -Dpolitica=random q2_descoberta.ServidorDiretorio; esperar 2
bg s1 q2_descoberta.ServidorCalc S1 7101
bg s2 q2_descoberta.ServidorCalc S2 7102
esperar 4
for i in 1 2 3 4 5 6 7 8 9 10; do fg q2_descoberta.ClienteCalc C1 --calc "$i + $i" | grep "RESULTADO:"; done
echo; echo "S1 processou $(grep -c 'CÁLCULO:' logs/calc-S1.log) | S2 processou $(grep -c 'CÁLCULO:' logs/calc-S2.log)  (sorteio: a divisão varia a cada execução, mas ambos recebem chamadas)"
