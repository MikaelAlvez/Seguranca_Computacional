#!/usr/bin/env bash
# Executa os casos de teste e salva a saída completa em evidencias/ (útil para conferir e para o vídeo).
# Uso: scripts/gerar_evidencias.sh [q1] [q2] [q3] [q4]   (sem argumentos: todos; leva alguns minutos)
cd "$(dirname "$0")/.." || exit 1
mkdir -p evidencias
grupos=("$@"); [ ${#grupos[@]} -eq 0 ] && grupos=(q1 q2 q3 q4)
for g in "${grupos[@]}"; do
  for c in 1 2 3; do
    echo ">>> executando ${g}_caso${c}.sh ..."
    bash "scripts/${g}_caso${c}.sh" > "evidencias/${g}_caso${c}.txt" 2>&1
  done
  if [ "$g" = "q1" ]; then
    echo ">>> executando q1_registrador.sh (caminhos de sucesso e de erro do registrador) ..."
    bash scripts/q1_registrador.sh > evidencias/q1_registrador.txt 2>&1
    echo ">>> executando q1_teste_completo.sh (63 verificações com entradas válidas e inválidas) ..."
    bash scripts/q1_teste_completo.sh > evidencias/q1_teste_completo.txt 2>&1
  fi
  if [ "$g" = "q4" ]; then
    echo ">>> executando q4_carga.sh (gerador de tráfego) ..."
    bash scripts/q4_carga.sh > evidencias/q4_carga.txt 2>&1
    echo ">>> executando q4_teste_completo.sh (98 verificações com entradas válidas e inválidas) ..."
    bash scripts/q4_teste_completo.sh > evidencias/q4_teste_completo.txt 2>&1
  fi
  if [ "$g" = "q3" ]; then
    echo ">>> executando q3_teste_completo.sh (180 verificações com entradas válidas e inválidas) ..."
    bash scripts/q3_teste_completo.sh > evidencias/q3_teste_completo.txt 2>&1
  fi
  if [ "$g" = "q2" ]; then
    echo ">>> executando q2_caso2_random.sh (variante com política random) ..."
    bash scripts/q2_caso2_random.sh > evidencias/q2_caso2_random.txt 2>&1
    echo ">>> executando q2_teste_completo.sh (65 verificações com entradas válidas e inválidas) ..."
    bash scripts/q2_teste_completo.sh > evidencias/q2_teste_completo.txt 2>&1
  fi
done
echo "Pronto: veja a pasta evidencias/"
