#!/usr/bin/env bash
# Q1 - Caso 3: nome inexistente / formato inválido / HMAC corrompido (Cliente 2 <-> DNS)
source "$(dirname "$0")/_lib.sh"; compilar; limpar_logs
titulo "Q1 CASO 3 - entrada inválida e falha de autenticação"
bg dns q1_dns.ServidorDNS; esperar 2
echo ">>> 3a) nome inexistente (servidor99)"
fg q1_dns.ClienteDNS C2 --consultar servidor99
echo; echo ">>> 3b) formato inválido ('ser vidor!')"
fg q1_dns.ClienteDNS C2 --consultar "ser vidor!"
echo; echo ">>> 3c) requisição com HMAC corrompido"
fg q1_dns.ClienteDNS C2 --consultar-hmac-corrompido servidor1
echo; echo ">>> 3c2) requisição com TEXTO CIFRADO adulterado no canal (falha de cifra/integridade)"
fg q1_dns.ClienteDNS C2 --consultar-cifrado-adulterado servidor1
echo; echo ">>> 3c3) handshake com cifra RSA corrompida (o servidor não consegue decifrar o KEYX)"
fg q1_dns.ClienteDNS C2 --handshake-invalido --consultar servidor1
echo; echo ">>> 3d) cliente comum tenta UPDATE (sem credencial de registrador)"
fg q1_dns.ClienteDNS C2 --tentar-update servidor1 6.6.6.6
echo; echo ">>> 3e) consulta normal ainda funciona (servidor não foi afetado)"
fg q1_dns.ClienteDNS C2 --consultar servidor1
mostrar_log dns-servidor
