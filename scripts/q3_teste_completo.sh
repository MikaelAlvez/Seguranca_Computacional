#!/usr/bin/env bash
# Q3 - TESTE COMPLETO: 60 arquivos a partir de P0 + pontas de cada faixa a partir de cada nó (saltos conferidos),
# 12 entradas inválidas com o anel ligado (nada pode trafegar), 18 mensagens malformadas/adulteradas no protocolo,
# e os dois tipos de adulteração do Caso 3. Os 6 nós sobem como processos independentes.
source "$(dirname "$0")/_lib.sh"; compilar; limpar_logs
titulo "Q3 - TESTE COMPLETO: informações válidas e inválidas"
fg q3_p2p.TesteQ3 completo; RC=$?
echo; echo "=========== O QUE OS NÓS REGISTRARAM (conferência nos logs) ==========="
for n in 0 1 2 3 4 5; do
  f="logs/p2p-P$n.log"
  echo "P$n: mensagens inválidas ignoradas=$(grep -c 'IGNORADA' $f) | falhas de integridade=$(grep -c 'FALHA DE AUTENTICA' $f) | erros 'Erro na entrada'=$(grep -c 'ERRO  Erro na entrada' $f)"
done
echo; echo "Código de saída do teste: $RC (0 = tudo passou)"
exit $RC
