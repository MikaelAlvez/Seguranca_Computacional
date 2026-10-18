# Prática Offline 1 — Segurança Computacional (UFERSA, 2026.2)

Quatro sistemas distribuídos em **Java 17+** (threads, sockets TCP e UDP), todos com **criptografia híbrida**
(RSA + AES + HMAC) e executados como **processos independentes**. Nenhuma biblioteca externa: só a JDK
(`javax.crypto`, `java.security`, `java.net`, `java.util.concurrent`).

| Q | Tema | Transporte | Componentes (cada um é um processo) |
|---|------|-----------|--------------------------------------|
| 1 | Mini-DNS com binding dinâmico | UDP | `ServidorDNS`, `ClienteDNS` (C1, C2, C3), `ClienteRegistrador` |
| 2 | Descoberta de serviços + load balancing | TCP | `ServidorDiretorio`, `ServidorCalc` (S1, S2, S3), `ClienteCalc` |
| 3 | Nomeação P2P em anel | TCP | `NoP2P` (P0 … P5) |
| 4 | CDN (Origem, Edge, GeoDNS, Cliente) | UDP + TCP | `ServidorOrigem`, `ServidorEdge` (8001-8003), `RouterGeoDNS`, `ClienteCDN` |

## 1. Como compilar e executar

Requisito: JDK 17 ou superior (`javac -version`). Execute **sempre da pasta raiz** do projeto.

```bash
# Linux / macOS / Git Bash
javac --release 17 -encoding UTF-8 -d out $(find src -name "*.java")
# Windows (PowerShell)
javac --release 17 -encoding UTF-8 -d out (Get-ChildItem -Recurse src -Filter *.java).FullName
```

**Testes automáticos** (sobem os processos, rodam os clientes e mostram os logs; Linux/macOS/Git Bash):

```bash
bash scripts/q1_caso1.sh     # ... q1_caso2.sh, q1_caso3.sh, q2_caso1..3, q3_caso1..3, q4_caso1..3
bash scripts/gerar_evidencias.sh        # roda os 12 casos e salva a saída em evidencias/
```
A pasta `evidencias/` já contém a saída completa de cada caso, gerada em uma execução real.
Os logs de cada componente ficam em `logs/<componente>.log` (envio, recebimento, validação de HMAC, falhas).

**Execução manual (um terminal por processo)** — exemplos:

```bash
# Q1                                              # Q3 (um terminal por nó; digite "buscar arquivo35.ext" no P0)
java -cp out q1_dns.ServidorDNS                   java -cp out q3_p2p.NoP2P 0   ...   java -cp out q3_p2p.NoP2P 5
java -cp out q1_dns.ClienteDNS C1 --interativo
java -cp out q1_dns.ClienteDNS C2 --assinar --ouvir 60
java -cp out q1_dns.ClienteRegistrador servidor1 192.168.0.111

# Q2                                              # Q4
java -cp out q2_descoberta.ServidorDiretorio      java -cp out q4_cdn.ServidorOrigem      (console: invalidar arquivo30.ext)
java -cp out q2_descoberta.ServidorCalc S1 7101   java -cp out q4_cdn.ServidorEdge 8001
java -cp out q2_descoberta.ServidorCalc S2 7102   java -cp out q4_cdn.RouterGeoDNS
java -cp out q2_descoberta.ClienteCalc C1 --calc "10 + 20"
                                                  java -cp out q4_cdn.ClienteCDN C1 --local BR-NE --get arquivo20.ext --get arquivo20.ext
```
Os programas que exigem argumentos (clientes, `ServidorCalc`, `ServidorEdge`, `NoP2P`) imprimem a sintaxe de uso quando chamados sem eles.

## 2. Segurança comum (pacote `comum`)

**Toda mensagem de aplicação** passa por `CanalSeguro`, nos dois transportes (`CanalTCP` / `ClienteUDPSeguro` / `ServidorUDPSeguro`).

1. **Handshake (assimétrico).** O cliente envia `HELLO`; o servidor responde com sua **chave pública RSA-2048**.
   O cliente sorteia (SecureRandom) uma chave **AES-128** e uma chave **HMAC-SHA256**, cifra as duas com
   **RSA-OAEP (SHA-256)** e envia (`KEYX`). Só o servidor, com a chave privada, as recupera. Ele confirma com a
   primeira mensagem já protegida (`HANDSHAKE_OK`). Só chaves públicas e dados cifrados circulam em texto claro.
2. **Mensagens (simétrico).** `pacote = sid . direção . seq . IV . cifrado . HMAC` (campos em Base64).
   Cifra **AES-128-CBC** com IV aleatório por mensagem; HMAC-SHA256 calculado sobre `sid.direção.seq.IV.cifrado`
   (**Encrypt-then-MAC**). O receptor **verifica o HMAC antes de decifrar**.

| Ameaça | Defesa |
|--------|--------|
| Espionagem | AES-CBC; chaves de sessão só trafegam cifradas com RSA |
| Alteração de mensagem / HMAC corrompido | HMAC-SHA256 verificado antes de decifrar → descarta e registra |
| Atacante sem a chave ("hash sem chave") | SHA-256 puro não passa na verificação do HMAC |
| Timing attack na comparação | `MessageDigest.isEqual` (tempo constante) |
| Replay (reenvio de pacote capturado) | número de sequência estritamente crescente por sessão |
| Reflexão (devolver ao emissor o próprio pacote) | "direção" (C/S) coberta pelo HMAC |
| Cliente comum agindo como administrador | credencial enviada **dentro do RSA** no handshake (ex.: `REGISTRADOR:token`, `ORIGEM:segredo`) |

## 3. Questões

### Q1 — Mini-DNS (UDP)
- `ServidorDNS` mantém `servidor1..10 → 192.168.0.10..100` e atende `QUERY`, `SUBSCRIBE`, `UPDATE`.
- **Binding dinâmico:** `UPDATE nome ip` só é aceito de sessão com credencial de registrador; atualiza o mapa e
  coloca o aviso numa **fila** (`LinkedBlockingQueue`); uma thread notificadora envia `NOTIFY` cifrado
  (chave própria de cada sessão) a todos os assinantes. O cliente tem uma thread de escuta que atualiza a tabela local.
- Nome inexistente/formato inválido → `Erro na entrada de dados. Tente outra vez!`. HMAC/cifra inválidos →
  pacote **descartado sem resposta** e log `FALHA DE INTEGRIDADE`.
- A notificação é enviada **a cada assinante** (unicast UDP, cifrada com a chave da sessão de cada um): um "multicast lógico"
  (publicar/assinar), não IP multicast/broadcast de rede.
- Casos: `q1_caso1.sh` (consulta), `q1_caso2.sh` (UPDATE → C1, C2, C3 notificados), `q1_caso3.sh` (servidor99, formato inválido,
  HMAC corrompido, **texto cifrado adulterado**, **handshake RSA corrompido**, UPDATE não autorizado).

**Teste completo da Q1** — `bash scripts/q1_teste_completo.sh` roda `q1_dns.TesteQ1` contra o `ServidorDNS` (processo separado):
63 verificações com entradas **válidas** (10 nomes do enunciado, UPDATE, nomes novos, limites de IP, notificação) e **inválidas**
(nome inexistente, formatos inválidos, IPs malformados, comando desconhecido, sem autorização) e **falhas de segurança**
(HMAC/cifra adulterados, replay, sessão falsa, datagrama lixo, handshake RSA corrompido). Mostra *esperado × obtido*, confere
o log do servidor e termina com código 0 só se tudo passar. Saída em `evidencias/q1_teste_completo.txt`.
Nota: nomes diferenciam maiúsculas de minúsculas (`SERVIDOR1` é rejeitado), ao contrário do DNS real.

**Quando o `ClienteRegistrador` retorna erro** (todos testados em `q1_registrador.sh`):

| Situação | Quem detecta | Resultado |
|----------|--------------|-----------|
| número de argumentos errado | cliente | imprime o uso; nenhum pacote é enviado |
| linha inválida no modo interativo | cliente | `Erro na entrada de dados. Tente outra vez!` |
| IP ou nome inválido | DNS | `ATUALIZAÇÃO REJEITADA`; o mapa não muda |
| credencial ausente ou errada (`--token-errado`) | DNS | `ATUALIZAÇÃO NEGADA` (`ERRO NAO_AUTORIZADO`) + `UPDATE NEGADO` no log do DNS |
| DNS fora do ar | cliente | 3 tentativas de handshake (~6 s) e aviso de servidor sem resposta |
| pacote sem HMAC válido | DNS | descarta sem responder; o cliente avisa "Sem resposta do DNS" |

Observação: o `UPDATE` também **cria** nomes novos (ex.: `servidor11`), pois o enunciado não restringe o registrador a nomes existentes.

### Q2 — Descoberta de serviços (TCP)
- `ServidorCalc` (S1-S3) registra-se no `ServidorDiretorio` por canal cifrado e **renova o registro a cada 5 s**
  (heartbeat). O diretório considera **ativo** o servidor que renovou nos últimos 15 s (lease).
- `DISCOVER|serviço` devolve só servidores ativos. **Round robin global** (contador atômico): o primeiro da lista
  alterna a cada consulta → S1, S2, S1, S2… (`-Dpolitica=random` troca por sorteio).
- Cada chamada abre **sessão cifrada nova** (RSA→AES+HMAC) com o servidor escolhido. Se ele estiver indisponível
  ou com HMAC inválido, o cliente o **rejeita e tenta o próximo** (failover); sem nenhum válido, informa o erro.
  Com `--sem-failover` o cliente apenas **rejeita a execução**, sem tentar outro nó.
- Serviços não cadastrados (ex.: `10 % 3` → "Calcular modulo") → `Erro na entrada de dados. Tente outra vez!`.
- Cálculos em `BigDecimal` (sem erro de ponto flutuante; divisão por zero tratada).
- Cada `ServidorCalc` registra em log quantos cálculos já processou, o que permite provar a **distribuição proporcional**.
- Casos: `q2_caso1.sh` (registro cifrado + descoberta de vários serviços), `q2_caso2.sh` (6 chamadas → resumo S1=3 / S2=3;
  `q2_caso2_random.sh` repete com `-Dpolitica=random`), `q2_caso3.sh` (operação inválida; **S3 indisponível**; **S3 adulterado**
  rejeitado com `--sem-failover`; só o nó adulterado restante; diretório segue respondendo).

**Teste completo da Q2** — `bash scripts/q2_teste_completo.sh` sobe o diretório e S1-S3 e roda `q2_descoberta.TesteQ2`:
65 verificações de protocolo com entradas **válidas** (descoberta dos 4 serviços e variações de escrita, round robin cíclico,
cálculos com decimais/negativos/números enormes, registro autorizado, servidor ativo × inativo pelo lease) e **inválidas**
(serviço inexistente, campos faltando/sobrando, injeção, divisão por zero, operandos inválidos, REGISTER sem credencial ou
malformado) e **falhas de segurança** (HMAC/cifra adulterados, replay, handshake RSA corrompido, lixo no handshake: a conexão é
encerrada e o servidor segue funcionando). Depois repete válidas/inválidas pelo cliente real (`ClienteCalc`) e confere os logs.
Saída em `evidencias/q2_teste_completo.txt`.

### Q3 — P2P em anel
- `Pi` guarda os arquivos `10i+1 … 10i+10`; conhece o sucessor `(i+1)%6` (o anel é percorrido no sentido horário) e o antecessor
  `(i+5)%6` (conhecido e exibido no log; o roteamento usa só o sucessor).
- A origem **valida o nome antes de abrir qualquer conexão** (`arquivoN.ext`, 1 ≤ N ≤ 60). Depois repassa `BUSCA` ao sucessor até o
  responsável, que responde **direto à origem**, informando também o nº de saltos. **Cada salto abre seu próprio canal seguro**
  (handshake RSA, chaves AES+HMAC novas): o nó seguinte decifra, valida o HMAC e **recifra** para o próximo.
- **Todo campo recebido** (origem, arquivo, saltos) é validado em cada salto; mensagem fora do padrão é **ignorada e registrada,
  nunca repassada**. (Isso corrigiu um defeito achado nos testes: antes, um `origem` inválido atravessava o anel inteiro e
  `origem=70000` derrubava a thread de um enlace.) Reiniciar um nó não quebra as buscas seguintes, pois não há canais em cache.
- Pacote adulterado: `--adulterar` (texto cifrado) ou `--adulterar-hmac` (HMAC) em um nó, ex.: `NoP2P 4 --adulterar`. O vizinho
  detecta, **descarta, registra e não repassa**; a origem avisa timeout.
- Casos (todos executam os 6 nós como processos separados e conferem o log de TODOS): `q3_caso1.sh` (P0→P1→P2→P3 para
  `arquivo35.ext`), `q3_caso2.sh` (`arquivo99.ext` e `arquivoABC.ext` com o anel ligado: nada trafega), `q3_caso3.sh` (P4→P5 com
  texto cifrado alterado e com HMAC inválido; P0 não recebe nada; o anel segue útil).
- **Teste completo da Q3** — `bash scripts/q3_teste_completo.sh` (ou `java q3_p2p.TesteQ3`): 180 verificações — os 60 arquivos a
  partir de P0 e as pontas de cada faixa a partir de cada nó, com o nº de saltos conferido `(responsável − origem) mod 6`; 12
  entradas inválidas no console com o anel ligado; 18 mensagens malformadas/adulteradas no protocolo; e o Caso 3 nas duas variantes.
  Saída em `evidencias/q3_teste_completo.txt`.

### Q4 — CDN
- Fluxo da Figura 1 do enunciado: (1) o cliente pergunta ao **Router/GeoDNS** (UDP cifrado) onde baixar; (2) recebe o Edge; (3) faz `GET`
  no **Edge** (TCP cifrado); (4) resposta HIT ou MISS; (5) em caso de MISS o Edge abre canal seguro com a **Origem**; (6) a Origem devolve o conteúdo.
- `ServidorOrigem` (TCP 8000): 1000 arquivos de 1024 bytes em `dados_origem/` (criados na 1ª execução); latência simulada de
  120 ms (`-Dcdn.latencia=0` desliga). Responde com um cabeçalho mínimo (`200 OK`/`404`/`Length`) para o Edge distinguir erros do conteúdo.
- `ServidorEdge` (8001-8003): cache em `ConcurrentHashMap` com **TTL de 10 s** (`-Dcdn.ttl=`) e faxina periódica. HIT responde da memória;
  MISS abre canal seguro com a Origem, guarda e responde. Arquivo com nome válido mas **inexistente na Origem** devolve `404 Not Found`
  (e não vai para o cache); Origem fora do ar devolve `502`, mas o que já está em cache **continua sendo servido** (resiliência).
- Resposta do Edge no formato do enunciado: `200 OK` / `Cache: HIT|MISS` / `Length: 1024` / linha em branco / conteúdo.
- `RouterGeoDNS` (UDP 5354): região → Edge (`BR-NE`→8001, `BR-SE`→8002, `US`→8003; região bem formada e desconhecida → Edge padrão 8001;
  região vazia/malformada ou arquivo inválido → `Erro na entrada de dados. Tente outra vez!`).
- **INVALIDATE** parte da Origem (console `invalidar` / `atualizar` ou `--admin`) e só é aceito pelo Edge se a sessão trouxer a credencial da
  Origem (senão `403 Forbidden`); `atualizar` reescreve o arquivo (v2) e invalida os 3 Edges → o próximo GET devolve a versão nova.
- `ClienteCDN` é também **gerador de tráfego**: `--carga N MAX` faz N requisições a arquivos sorteados entre 1 e MAX (semente fixa) e imprime
  o resumo (HIT, MISS, tempo médio de cada, taxa de acerto).
- Casos: `q4_caso1.sh` (MISS ≈ 460 ms × HIT ≈ 10 ms com a latência simulada; cliente dos EUA vai a outro Edge), `q4_caso2.sh` (TTL e
  INVALIDATE), `q4_caso3.sh` (`/arquivoXYZ.ext` sem consultar a Origem; hash sem chave → Edge fecha o socket; handshake RSA inválido;
  cache segue íntegro), `q4_carga.sh` (tráfego e taxa de acerto).
- **Teste completo da Q4** — `bash scripts/q4_teste_completo.sh` (ou `java q4_cdn.TesteQ4`): sobe Origem, 3 Edges e Router como processos
  separados (TTL 6 s, latência simulada desligada) e executa 98 verificações: roteamento nas 3 regiões e entradas inválidas, HIT×MISS nos 3
  Edges com formato do protocolo e HMAC nas duas pontas (HIT mais rápido que MISS pela mediana medida), TTL, INVALIDATE seletivo e com
  consistência (v2), 403 para quem não é a Origem, 15 pedidos inválidos (a Origem nem é consultada), 404, handshake/HMAC/cifra/replay
  adulterados (o Edge encerra a conexão e o cache fica intacto), 96 requisições concorrentes e a Origem fora do ar. Saída em
  `evidencias/q4_teste_completo.txt`.

## 4. Threads e coleções thread-safe (como nas práticas 2.5–2.8)
| Onde | Coleção | Por quê |
|------|---------|---------|
| mapa do DNS, sessões UDP, cache do Edge, buscas pendentes P2P | `ConcurrentHashMap` | leituras/escritas concorrentes sem bloqueio global |
| assinantes do DNS | `CopyOnWriteArrayList` | muitas leituras (cada NOTIFY) e poucas escritas |
| avisos do DNS; respostas do cliente DNS | `LinkedBlockingQueue` | produtor/consumidor entre threads |
| contador do round robin | `AtomicInteger` | incremento atômico sem `synchronized` |

Threads: pool de 8 por servidor UDP; uma thread por conexão nos servidores TCP; threads de escuta, notificação,
heartbeat e faxina de TTL.

## 5. Limitações conhecidas (didáticas)
- A chave pública do servidor chega **sem certificado**: um atacante ativo (MitM) poderia trocá-la no handshake.
  Em produção usaríamos certificados X.509/PKI ou *pinning* (aula de Certificados Digitais).
- Tokens/segredos (`Config.java`) são fixos no código só para simular credenciais pré-distribuídas.
- A Origem atende qualquer cliente que complete o handshake (não exige credencial dos Edges); em produção só os Edges deveriam alcançá-la.
- Sem sigilo perfeito (PFS); o anti-replay exige sequência estritamente crescente (em UDP um pacote reordenado é descartado).
- O token de registro dos servidores de cálculo é único e compartilhado: quem o possuir pode registrar um nome já existente e
  sobrescrever a entrada de outro servidor (em produção, cada servidor teria credencial/certificado próprio).
- Anel P2P sem detecção de falha de nós; cache da CDN sem política de remoção além do TTL; testes em `localhost`.

## 6. Problemas comuns
- **Porta em uso:** altere com `-Ddns.porta=`, `-Ddir.porta=`, `-Dp2p.base=`, `-Dcdn.router.porta=` (a 5353 do enunciado
  costuma ser ocupada pelo mDNS; por isso o Router usa 5354).
- **Q3, `Conexão recusada` ao buscar:** algum nó do anel não está rodando. Suba os 6 (`NoP2P 0` … `NoP2P 5`, um terminal ou
  configuração de execução por nó) antes de digitar `buscar arquivoN.ext`. Para `arquivo35.ext` bastam P0, P1, P2 e P3.
- **Eclipse: `TesteQ3` diz "nó P0 não subiu ... não foi possível localizar a classe":** o `TesteQ3` sobe os 6 nós como processos
  separados e precisa achar as classes compiladas. Ele usa a origem do próprio código (funciona com pacote renomeado e com projeto
  modular, isto é, com `module-info.java`). Antes de rodá-lo, **encerre qualquer `NoP2P` que esteja rodando** (portas 9100–9105).
- **Q4, arquivos aparecem como `[v2]` ou `[v3]`:** o comando `atualizar` da Origem reescreve o arquivo mestre em disco. Para voltar tudo
  para `v1`, suba a Origem com `--resetar` (`java q4_cdn.ServidorOrigem --resetar`); os scripts e o `TesteQ4` já fazem isso.
- **`TesteQ3`/`TesteQ4`: "portas já em uso":** os testes sobem os componentes sozinhos e conferem as portas antes (TCP 9100-9105 para a
  Q3; TCP 8000-8003 e UDP 5354 para a Q4). Se alguma estiver ocupada há processo antigo rodando (uma execução anterior interrompida pode
  deixar processos-filho órfãos). Eclipse: botão vermelho em cada execução antiga. Linux/macOS: `jps -l` e `kill <PID>` (ou
  `lsof -ti :PORTA | xargs kill`). Windows: `netstat -ano | findstr :PORTA` e `taskkill /PID <PID> /F`.
- **Acentos como `?`/`�`:** use terminal em UTF-8 (os scripts já tentam `LC_ALL=C.UTF-8`).
