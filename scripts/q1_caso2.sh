#!/usr/bin/env bash
# Q1 - Caso 2: binding dinâmico + notificação a C1, C2, C3
source "$(dirname "$0")/_lib.sh"; compilar; limpar_logs
titulo "Q1 CASO 2 - binding dinâmico e notificação"
bg dns q1_dns.ServidorDNS; esperar 2
# C1, C2 e C3 são processos independentes: assinam e ficam ouvindo 8 s
for c in C1 C2 C3; do
  ( $JAVA q1_dns.ClienteDNS $c --assinar --consultar servidor1 --ouvir 8 > "logs/saida-$c.txt" 2>&1 ) &
  PIDS+=($!)
done
esperar 4
echo ">>> Registrador atualiza servidor1 -> 192.168.0.111"
fg q1_dns.ClienteRegistrador servidor1 192.168.0.111
esperar 6
for c in C1 C2 C3; do echo; echo "===== saída do cliente $c ====="; cat "logs/saida-$c.txt"; done
mostrar_log dns-servidor
