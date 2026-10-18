#!/usr/bin/env bash
# Q1 - TESTE COMPLETO com informações VÁLIDAS e INVÁLIDAS (matriz com esperado x obtido) + conferência do log do servidor
source "$(dirname "$0")/_lib.sh"; compilar; limpar_logs
titulo "Q1 - TESTE COMPLETO: informações válidas e inválidas"
bg dns q1_dns.ServidorDNS; esperar 2
fg q1_dns.TesteQ1; RC=$?
contar() { grep -c "$1" logs/dns-servidor.log; }
echo; echo "=========== O QUE O SERVIDOR REGISTROU (conferência no log) ==========="
echo "Consultas resolvidas ........................ $(contar 'Resolução:')"
echo "Entradas rejeitadas (Erro na entrada...) .... $(contar 'ERRO  Erro na entrada')"
echo "UPDATE negado (sem credencial) .............. $(contar 'UPDATE NEGADO')"
echo "Bindings dinâmicos aplicados ................ $(contar 'BINDING DINÂMICO')"
echo "Falhas de integridade (HMAC/cifra/replay) ... $(contar 'FALHA DE INTEGRIDADE')"
echo "Falhas de handshake RSA ..................... $(contar 'FALHA NO HANDSHAKE')"
echo "Pacotes de sessão desconhecida/malformados .. $(contar 'sessão desconhecida')"
echo "Replay: servidor6 aplicado quantas vezes? ... $(contar 'BINDING DINÂMICO: servidor6') (esperado: 1)"
echo; echo "Código de saída do teste: $RC (0 = tudo passou)"
exit $RC
