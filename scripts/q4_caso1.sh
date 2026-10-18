#!/usr/bin/env bash
# Q4 - Caso 1: cache MISS x HIT em canal cifrado (Cliente, Router/GeoDNS, Edge, Origem)
source "$(dirname "$0")/_lib.sh"; compilar; limpar_logs
titulo "Q4 CASO 1 - cache HIT vs MISS em canal cifrado"
bg origem q4_cdn.ServidorOrigem --resetar
bg edge1 q4_cdn.ServidorEdge 8001
bg edge3 q4_cdn.ServidorEdge 8003
bg router q4_cdn.RouterGeoDNS
esperar 4
echo ">>> Cliente do Nordeste (BR-NE): GET (MISS) e GET (HIT) no mesmo arquivo"
fg q4_cdn.ClienteCDN C1 --local BR-NE --get arquivo20.ext --get arquivo20.ext
echo; echo ">>> Cliente dos EUA (US): o GeoDNS o encaminha a OUTRO Edge (8003), que tem cache próprio (MISS)"
fg q4_cdn.ClienteCDN C2 --local US --get arquivo20.ext
mostrar_log cdn-edge-8001
mostrar_log cdn-edge-8003
mostrar_log cdn-origem
mostrar_log cdn-router
