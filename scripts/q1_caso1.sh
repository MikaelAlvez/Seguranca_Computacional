#!/usr/bin/env bash
# Q1 - Caso 1: consulta de resolução de nome (Cliente 1 <-> Servidor DNS)
source "$(dirname "$0")/_lib.sh"; compilar; limpar_logs
titulo "Q1 CASO 1 - consulta servidor1"
bg dns q1_dns.ServidorDNS; esperar 2
fg q1_dns.ClienteDNS C1 --consultar servidor1
mostrar_log dns-servidor
