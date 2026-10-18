#!/usr/bin/env bash
# Q2 - Caso 2: load balancing ROUND ROBIN entre S1 e S2 (Cliente 1 e Cliente 2; 10 + 20, 5 * 4, ...)
source "$(dirname "$0")/_lib.sh"; compilar; limpar_logs
titulo "Q2 CASO 2 - chamada de serviço com round robin"
bg diretorio q2_descoberta.ServidorDiretorio; esperar 2
bg s1 q2_descoberta.ServidorCalc S1 7101
bg s2 q2_descoberta.ServidorCalc S2 7102
esperar 4
echo ">>> 1ª chamada, Cliente 1: 10 + 20   (esperado: S1)"
fg q2_descoberta.ClienteCalc C1 --calc "10 + 20"
echo; echo ">>> 2ª chamada, Cliente 2: 5 * 4     (esperado: S2)"
fg q2_descoberta.ClienteCalc C2 --calc "5 * 4"
echo; echo ">>> mais 4 chamadas alternadas entre os clientes (e as 4 operações)"
fg q2_descoberta.ClienteCalc C1 --calc "7 - 2"
fg q2_descoberta.ClienteCalc C2 --calc "9 / 4"
fg q2_descoberta.ClienteCalc C1 --calc "3 + 4"
fg q2_descoberta.ClienteCalc C2 --calc "8 * 2"
echo; echo "=================== RESUMO DA DISTRIBUIÇÃO ==================="
grep -E "DISCOVER '" logs/diretorio.log | sed -E 's/^\[[0-9:.]+\] \[diretorio\] INFO  //'
echo "S1 processou $(grep -c 'CÁLCULO:' logs/calc-S1.log) cálculo(s) | S2 processou $(grep -c 'CÁLCULO:' logs/calc-S2.log) cálculo(s)  (6 chamadas -> 3 para cada: distribuição proporcional)"
mostrar_log calc-S1
mostrar_log calc-S2
