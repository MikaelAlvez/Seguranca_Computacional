#!/usr/bin/env bash
# Q4 - Caso 3: requisição inexistente/fora do padrão, HMAC ausente (hash sem chave) e handshake inválido
source "$(dirname "$0")/_lib.sh"; compilar; limpar_logs
titulo "Q4 CASO 3 - entrada inválida e erro de cifra na borda"
bg origem q4_cdn.ServidorOrigem --resetar
bg edge1 q4_cdn.ServidorEdge 8001
esperar 4
echo ">>> 3a) nome fora do padrão: GET /arquivoXYZ.ext (a Origem NÃO deve ser consultada)"
fg q4_cdn.ClienteCDN C3 --edge 127.0.0.1:8001 --get arquivoXYZ.ext
echo; echo ">>> 3b) cache normal: GET /arquivo20.ext (MISS), depois ATAQUE: GET com SHA-256 sem chave no lugar do HMAC"
fg q4_cdn.ClienteCDN C3 --edge 127.0.0.1:8001 --get arquivo20.ext
fg q4_cdn.ClienteCDN C4 --edge 127.0.0.1:8001 --get-sem-hmac arquivo21.ext
echo; echo ">>> 3c) handshake com erro de decifragem RSA"
fg q4_cdn.ClienteCDN C5 --edge 127.0.0.1:8001 --handshake-invalido --get arquivo22.ext
echo; echo ">>> 3d) após os ataques, o cache continua íntegro: GET /arquivo20.ext deve ser HIT"
fg q4_cdn.ClienteCDN C3 --edge 127.0.0.1:8001 --get arquivo20.ext
mostrar_log cdn-edge-8001
echo; echo "----- a Origem só deve ter servido arquivo20 (nenhuma consulta de XYZ/21/22) -----"
grep -E "entregou" logs/cdn-origem.log
