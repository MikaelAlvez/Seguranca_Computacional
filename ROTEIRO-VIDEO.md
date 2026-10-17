# Roteiro sugerido para o vídeo (máx. 8 min)

> Sugestão de ordem e de falas. Adapte com as suas palavras e a sua experiência — o vídeo é o instrumento avaliativo
> e você precisa dominar o que está mostrando. Dica: abra `evidencias/` e `README.md` como apoio, mas rode ao vivo.

| Tempo | O que mostrar | O que falar |
|-------|---------------|-------------|
| 0:00–0:40 | Estrutura do projeto | Java 17, threads, sockets TCP/UDP, `javax.crypto` (RSA-OAEP, AES-CBC, HMAC-SHA256). 4 questões, componentes como processos independentes. |
| 0:40–1:40 | `comum/CanalSeguro.java` | Handshake híbrido (RSA entrega chaves AES+HMAC) e pacote `sid.dir.seq.IV.cifrado.HMAC`; Encrypt-then-MAC; HMAC verificado antes de decifrar; `isEqual` em tempo constante; seq anti-replay. |
| 1:40–3:00 | **Q1** `q1_caso1/2/3.sh` (ou 3 terminais) | Consulta; UPDATE do registrador → NOTIFY a C1, C2, C3 (fila + thread notificadora); erros: nome inexistente, formato inválido, HMAC corrompido (descartado sem resposta), UPDATE sem credencial. |
| 3:00–4:20 | **Q2** casos 1, 2, 3 | Registro cifrado e lease/heartbeat; round robin S1,S2,S1,S2; failover; S3 adulterado rejeitado; "Calcular modulo" inválido. |
| 4:20–5:30 | **Q3** casos 1, 2, 3 | Anel, faixas de arquivos, handshake por enlace e recifragem a cada salto; validação antes de abrir conexão; pacote adulterado descartado em P5 e P0 não recebe nada. |
| 5:30–7:00 | **Q4** casos 1, 2, 3 | MISS vs HIT (tempos), GeoDNS escolhendo Edge, TTL, INVALIDATE com credencial da Origem e versão v2, hash sem chave → socket fechado, cache íntegro. |
| 7:00–8:00 | Código + conclusão | Organização por pacote; coleções thread-safe; limitações (MitM no handshake sem certificado); dificuldades e lições. |

## Para cada programa, diga (exigência do enunciado)
- **Erros/exceções que o programa captura:** `ErroIntegridadeException` (HMAC, seq/replay, RSA, formato), `SocketTimeoutException`
  (UDP sem resposta), `ConnectException` (servidor fora do ar), `NumberFormatException` (porta/operandos), entrada inválida
  (`Erro na entrada de dados. Tente outra vez!`), divisão por zero.
- **Tecnologias:** JDK 17, `java.net` (Socket/ServerSocket/DatagramSocket), `javax.crypto`, `java.util.concurrent`.
- **Implementa tudo?** Sim, os 12 casos de teste (evidências em `evidencias/`), com os extras de autorização por credencial e anti-replay.

## Possíveis pontos para "dificuldades e lições aprendidas"
- O round robin precisa ser **global**: com contador por serviço, "soma" e "multiplicação" caíam sempre em S1.
- UDP não garante ordem: o anti-replay por sequência exige enviar e numerar sob o mesmo `synchronized`.
- Argumentos com acento dependem do locale/encoding do terminal (UTF-8).
- Separar "descartar sem responder" (UDP/P2P) de "encerrar a conexão" (Edge TCP) conforme o enunciado.
- Sem certificado, o handshake RSA continua vulnerável a MitM.
