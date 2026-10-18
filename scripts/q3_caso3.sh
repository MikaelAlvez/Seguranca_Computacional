#!/usr/bin/env bash
# P4 envia a P5 texto cifrado alterado e, depois, HMAC inválido: P5 detecta, descarta, não repassa a P0 e grava no log
source "$(dirname "$0")/_lib.sh"; compilar; limpar_logs
titulo "Q3 CASO 3 - falha de integridade / pacote adulterado"
fg q3_p2p.TesteQ3 caso3
