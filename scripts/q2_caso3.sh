#!/usr/bin/env bash
# Q2 - Caso 3: operação inválida; S3 INDISPONÍVEL; S3 ADULTERADO (HMAC corrompido); tudo sem travar os demais componentes
source "$(dirname "$0")/_lib.sh"; compilar; limpar_logs
titulo "Q2 CASO 3 - entrada inválida, servidor indisponível e HMAC corrompido"
bg diretorio q2_descoberta.ServidorDiretorio; esperar 2
bg s1 q2_descoberta.ServidorCalc S1 7101; S1_PID=${PIDS[-1]}
bg s2 q2_descoberta.ServidorCalc S2 7102; S2_PID=${PIDS[-1]}
bg s3 q2_descoberta.ServidorCalc S3 7103; S3_PID=${PIDS[-1]}
esperar 4

echo ">>> 3a) ENTRADA INVÁLIDA: operação não cadastrada 'Calcular modulo' (10 % 3)"
fg q2_descoberta.ClienteCalc C3 --calc "10 % 3"
echo; echo ">>> 3b) ENTRADA INVÁLIDA: expressão que não é uma conta"
fg q2_descoberta.ClienteCalc C3 --calc "dez mais vinte"

echo; echo ">>> 3c) S3 INDISPONÍVEL: derrubamos o S3; o diretório ainda o lista (lease de 15 s). A 3ª chamada recebe S3 em 1º lugar."
kill $S3_PID; sleep 1
fg q2_descoberta.ClienteCalc C3 --calc "10 + 20"
fg q2_descoberta.ClienteCalc C3 --calc "7 - 2"
fg q2_descoberta.ClienteCalc C3 --calc "5 * 4"

echo; echo ">>> 3d) S3 ADULTERADO: sobe de novo, agora enviando HMAC corrompido. A 3ª chamada (sem failover) recebe S3 em 1º lugar e REJEITA a execução."
bg s3x q2_descoberta.ServidorCalc S3 7103 --adulterado; esperar 3
fg q2_descoberta.ClienteCalc C3 --calc "10 + 20"
fg q2_descoberta.ClienteCalc C3 --calc "7 - 2"
fg q2_descoberta.ClienteCalc C3 --sem-failover --calc "5 * 4"

echo; echo ">>> 3e) Só o S3 adulterado resta (derrubamos S1 e S2 e esperamos o lease expirar, 16 s): erro tratado, nada trava."
kill $S1_PID $S2_PID; sleep 16
fg q2_descoberta.ClienteCalc C3 --calc "10 + 20"
echo; echo ">>> 3f) O diretório continua respondendo normalmente (demais componentes não travaram)"
fg q2_descoberta.ClienteCalc C3 --descobrir "Calcular soma"
mostrar_log diretorio
