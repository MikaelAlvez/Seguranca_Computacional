#!/usr/bin/env bash
# P1 (arquivo99.ext) e P4 (arquivoABC.ext) com o anel inteiro ligado: rejeita antes de criptografar/conectar e nada trafega
source "$(dirname "$0")/_lib.sh"; compilar; limpar_logs
titulo "Q3 CASO 2 - entrada inválida"
fg q3_p2p.TesteQ3 caso2
