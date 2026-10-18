#!/usr/bin/env bash
# Q4 - Caso 2: expiração por TTL (10 s) e invalidação ativa (INVALIDATE da Origem)
source "$(dirname "$0")/_lib.sh"; compilar; limpar_logs
titulo "Q4 CASO 2 - TTL e INVALIDATE"
bg origem q4_cdn.ServidorOrigem --resetar
bg edge1 q4_cdn.ServidorEdge 8001
esperar 4
echo ">>> Parte A: TTL. GET (MISS) -> GET (HIT) -> espera 11 s -> GET (MISS por expiração)"
fg q4_cdn.ClienteCDN C1 --edge 127.0.0.1:8001 --get arquivo30.ext --get arquivo30.ext --dormir 11 --get arquivo30.ext
echo; echo ">>> Parte B: INVALIDATE ativo. GET (HIT) -> Origem atualiza o arquivo e invalida -> GET (MISS, versão nova)"
fg q4_cdn.ClienteCDN C1 --edge 127.0.0.1:8001 --get arquivo30.ext
fg q4_cdn.ServidorOrigem --admin --edges 8001 --cmd "atualizar arquivo30.ext"
fg q4_cdn.ClienteCDN C1 --edge 127.0.0.1:8001 --get arquivo30.ext
mostrar_log cdn-edge-8001
