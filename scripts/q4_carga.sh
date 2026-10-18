#!/usr/bin/env bash
# Q4 - Gerador de tráfego: 30 requisições a 10 arquivos sorteados -> resumo HIT x MISS (latência simulada desligada)
source "$(dirname "$0")/_lib.sh"; compilar; limpar_logs
titulo "Q4 - gerador de tráfego e taxa de acerto do cache"
bg origem -Dcdn.latencia=0 q4_cdn.ServidorOrigem --resetar
bg edge1 q4_cdn.ServidorEdge 8001
bg router q4_cdn.RouterGeoDNS
esperar 4
fg q4_cdn.ClienteCDN C1 --local BR-NE --carga 30 10 | grep -E "ROUTER indicou|RESUMO" | sed -E 's/^\[[0-9:.]+\] \[[a-z0-9-]+\] //'
