#!/usr/bin/env bash
# Q2 - Caso 1: S1, S2, S3 registram no Diretório (RSA+AES+HMAC); Cliente 1 descobre "Calcular soma" etc.
source "$(dirname "$0")/_lib.sh"; compilar; limpar_logs
titulo "Q2 CASO 1 - descoberta de serviço e cifra híbrida no diretório"
bg diretorio q2_descoberta.ServidorDiretorio; esperar 2
bg s1 q2_descoberta.ServidorCalc S1 7101
bg s2 q2_descoberta.ServidorCalc S2 7102
bg s3 q2_descoberta.ServidorCalc S3 7103
esperar 4
fg q2_descoberta.ClienteCalc C1 --descobrir "Calcular soma" --descobrir "Calcular multiplicação"
mostrar_log diretorio
