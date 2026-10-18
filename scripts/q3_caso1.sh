#!/usr/bin/env bash
# Origem P0, arquivo35.ext: P0 -> P1 -> P2 -> P3 (6 nós como processos independentes; o teste digita o comando no console de P0)
source "$(dirname "$0")/_lib.sh"; compilar; limpar_logs
titulo "Q3 CASO 1 - busca válida com criptografia salto a salto"
fg q3_p2p.TesteQ3 caso1
