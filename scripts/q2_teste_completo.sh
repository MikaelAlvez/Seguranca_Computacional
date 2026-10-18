#!/usr/bin/env bash
# Q2 - TESTE COMPLETO com informações VÁLIDAS e INVÁLIDAS:
#   (1) matriz de protocolo (esperado x obtido) com TesteQ2;
#   (2) o mesmo, pelo cliente REAL (ClienteCalc);
#   (3) conferência do que cada componente registrou no log.
source "$(dirname "$0")/_lib.sh"; compilar; limpar_logs
titulo "Q2 - TESTE COMPLETO: informações válidas e inválidas"
bg diretorio q2_descoberta.ServidorDiretorio; esperar 2
bg s1 q2_descoberta.ServidorCalc S1 7101
bg s2 q2_descoberta.ServidorCalc S2 7102
bg s3 q2_descoberta.ServidorCalc S3 7103
esperar 4

echo; echo "################ PARTE 1: matriz de protocolo (TesteQ2) ################"
fg q2_descoberta.TesteQ2; RC=$?

echo; echo "################ PARTE 2: cliente REAL (ClienteCalc), expressões válidas e inválidas ################"
for expr in "10 + 20" "7 - 2" "5 * 4" "9 / 4" "7.5 + 2.25" "-3 * 4" "10/2" "10 / 0" "10 % 3" "dez mais vinte" "10 +" ""; do
  echo "--- expressão: '$expr'"
  fg q2_descoberta.ClienteCalc C1 --calc "$expr" | grep -E "RESULTADO|ERRO |SEGUR" | sed -E 's/^\[[0-9:.]+\] \[[a-z0-9-]+\] //'
done
echo "--- descobrir serviço válido e inválido"
fg q2_descoberta.ClienteCalc C1 --descobrir "Calcular soma" --descobrir "Calcular multiplicação" --descobrir "Calcular modulo" --descobrir "" \
  | grep -E "DESCOBERTA|ERRO " | sed -E 's/^\[[0-9:.]+\] \[[a-z0-9-]+\] //'

echo; echo "################ PARTE 3: o que os componentes registraram ################"
contar() { grep -c "$2" "logs/$1.log"; }
echo "Diretório: REGISTER negado ................ $(contar diretorio 'REGISTER NEGADO')   (esperado: 2)"
echo "Diretório: entradas rejeitadas ............ $(contar diretorio 'ERRO  Erro na entrada')"
echo "Diretório: eventos de segurança (SEGUR) ... $(contar diretorio 'SEGUR')   (HMAC/cifra/replay/handshake)"
echo "S1: cálculos processados .................. $(contar calc-S1 'CÁLCULO:')"
echo "S1: entradas rejeitadas ................... $(contar calc-S1 'ERRO  Erro na entrada')"
echo "S1: eventos de segurança (SEGUR) .......... $(contar calc-S1 'SEGUR')"
echo "S2 / S3: cálculos processados ............. $(contar calc-S2 'CÁLCULO:') / $(contar calc-S3 'CÁLCULO:')"
echo; echo "Código de saída do TesteQ2: $RC (0 = tudo passou)"
exit $RC
