#!/usr/bin/env bash
# Q4 - TESTE COMPLETO: Origem, 3 Edges e Router como processos independentes (TTL = 6 s, latência simulada desligada).
# 98 verificações: roteamento GeoDNS, HIT x MISS, TTL, INVALIDATE, requisições inválidas, 404, erro de cifra/HMAC/replay,
# tráfego concorrente e resiliência com a Origem fora do ar.
source "$(dirname "$0")/_lib.sh"; compilar; limpar_logs
titulo "Q4 - TESTE COMPLETO: informações válidas e inválidas"
fg q4_cdn.TesteQ4; RC=$?
echo; echo "Código de saída do teste: $RC (0 = tudo passou)"
exit $RC
