#!/usr/bin/env bash
# Funções auxiliares dos scripts de teste (Linux/macOS/Git-Bash).
RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$RAIZ" || exit 1
# Argumentos com acento (ex.: "Calcular multiplicação") exigem um locale UTF-8 no terminal/JVM.
for loc in C.UTF-8 C.utf8 en_US.UTF-8 en_US.utf8 pt_BR.UTF-8 pt_BR.utf8; do
  if locale -a 2>/dev/null | grep -qix "$loc"; then export LC_ALL="$loc"; break; fi
done
JAVA="java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp out"
PIDS=()

compilar() {
  mkdir -p out logs
  javac --release 17 -encoding UTF-8 -d out $(find src -name '*.java') || exit 1
}

limpar_logs() { mkdir -p logs; rm -f logs/*.log logs/*.err; }

# bg <nome> <classe> [args...]  -> sobe um componente como PROCESSO independente (em segundo plano)
bg() {
  local nome="$1"; shift
  $JAVA "$@" >/dev/null 2>"logs/$nome.err" </dev/null &
  PIDS+=($!)
}

# fg <classe> [args...] -> executa um cliente em primeiro plano (a saída aparece no terminal)
fg() { $JAVA "$@"; }

esperar() { sleep "${1:-2}"; }

titulo() { echo; echo "################ $* ################"; }

mostrar_log() { echo; echo "----- logs/$1.log -----"; cat "logs/$1.log"; }

parar_tudo() {
  for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; done
  sleep 0.5
  for p in "${PIDS[@]}"; do kill -9 "$p" 2>/dev/null; done
  true
}
trap parar_tudo EXIT
