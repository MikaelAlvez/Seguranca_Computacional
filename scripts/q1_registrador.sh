#!/usr/bin/env bash
# Q1 - ClienteRegistrador: caminhos de SUCESSO e de ERRO (o Caso 2 do enunciado usa só o caminho de sucesso)
source "$(dirname "$0")/_lib.sh"; compilar; limpar_logs
titulo "Q1 - ClienteRegistrador: sucesso e erros"
bg dns q1_dns.ServidorDNS; esperar 2
echo ">>> R1) IP inválido (999.1.1.1) -> o DNS recusa e o mapa não muda"
fg q1_dns.ClienteRegistrador servidor1 999.1.1.1
echo; echo ">>> R2) nome inválido ('ser vidor!')"
fg q1_dns.ClienteRegistrador "ser vidor!" 192.168.0.5
echo; echo ">>> R3) registrador com credencial ERRADA -> NAO_AUTORIZADO"
fg q1_dns.ClienteRegistrador --token-errado servidor1 192.168.0.7
echo; echo ">>> R4) número de argumentos errado (só o nome)"
fg q1_dns.ClienteRegistrador servidor1
echo; echo ">>> conferência: após R1-R4 o servidor1 continua com o IP original"
fg q1_dns.ClienteDNS C1 --consultar servidor1 | grep -E "RESOLUÇÃO|rejeitou"
echo; echo ">>> R5) atualização VÁLIDA: servidor1 -> 192.168.0.111, conferida por uma consulta"
fg q1_dns.ClienteRegistrador servidor1 192.168.0.111
fg q1_dns.ClienteDNS C1 --consultar servidor1 | grep -E "RESOLUÇÃO|rejeitou"
echo; echo ">>> R6) nome novo (o binding dinâmico também cria nomes): servidor11 -> 192.168.0.110"
fg q1_dns.ClienteRegistrador servidor11 192.168.0.110
fg q1_dns.ClienteDNS C1 --consultar servidor11 | grep -E "RESOLUÇÃO|rejeitou"
echo; echo ">>> R7) modo interativo: uma linha válida, duas inválidas e 'sair'"
printf 'servidor2 192.168.0.222\nabc\nservidor3\nsair\n' | fg q1_dns.ClienteRegistrador
echo; echo ">>> R8) DNS fora do ar (aguarde ~6 s: 3 tentativas de handshake)"
kill ${PIDS[0]}; sleep 1
fg q1_dns.ClienteRegistrador servidor1 192.168.0.1
mostrar_log dns-servidor
