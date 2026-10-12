# Tolerância a falhas contra um serviço externo instável

Circuit breaker, retry, bulkhead, time limiter e fallback sobre uma dependência
que falha de formas diferentes sob demanda. O assunto não é ligar as anotações —
é a **ordem em que os decoradores se compõem** e os casos em que a proteção
piora o sistema em vez de proteger.

Cinco resultados medidos que mudam a conversa:

**Sem bulkhead, uma dependência lenta derruba o endpoint que não usa nada.** Com
150 chamadas concorrentes contra uma transportadora que responde em 1,5 s, o
`/catalog` — que não chama ninguém — respondeu **1 requisição em 3 segundos, e
ela levou 5872 ms**. Com um bulkhead de 10 sobre o mesmo cenário: **3066
requisições, p50 de 0,84 ms**. O bulkhead não acelera a dependência; ele limita
quanto do contêiner ela tem direito de ocupar.

**A ordem do retry e do circuit breaker decide quando o circuito abre.** Cinco
chamadas lógicas contra um serviço que responde 500, três tentativas cada. Com o
retry por fora (o default do Resilience4j), o breaker registra **10 chamadas,
abre** e as duas últimas chamadas nem saem — **10 requisições** chegaram ao
serviço. Com o retry por dentro, ele registra **5**, continua **fechado**, e
**15 requisições** chegaram ao serviço que está morrendo.

**Um 404 de negócio abre o circuito para todo mundo.** Vinte consultas de SKU
inexistente, serviço perfeitamente saudável: com a configuração default — "toda
exceção é falha" — o circuito abriu e **10 das 20 chamadas foram recusadas pela
própria aplicação**. Declarando `recordExceptions`, 20 de 20 receberam o 404 que
pediram.

**Retentar um circuito aberto é a falha mais lenta do sistema.** Um circuito
aberto existe para falhar rápido. Com a configuração default do retry, que
retenta qualquer exceção, a chamada recusada custou **1500 ms** — o backoff
inteiro, para ser recusada de novo por um breaker que não tocou na rede. Com uma
whitelist de exceções retentáveis: **17 µs**.

**`@TimeLimiter` em método bloqueante não protege nada — e quebra a chamada.** O
aspecto só sabe embrulhar `CompletionStage`. Na assinatura bloqueante ele lança
`IllegalReturnTypeException` na primeira chamada, em produção, não na subida.
Na assíncrona ele cumpre o prazo de 500 ms — e a transportadora **serve a
requisição de qualquer forma**: o limite limita a espera, nunca o trabalho.

## Tecnologias e bibliotecas

| Tecnologia | Para que serve aqui |
| --- | --- |
| Java 21 | virtual threads nos experimentos de carga e concorrência |
| Spring Boot 3.5 | aplicação, AOP e configuração das políticas |
| Resilience4j 2.3 | circuit breaker, retry, bulkhead, time limiter, fallback |
| WireMock 3.9 | a transportadora instável, em container, programada em runtime |
| Micrometer + Actuator | uma série por política em `/actuator/prometheus` |
| Testcontainers | o WireMock real nos testes de integração |
| JUnit 5 + AssertJ | testes |

Sem banco de dados: o que o projeto precisa provar não depende de persistência.

## Pré-requisitos

- Java 21
- Docker e Docker Compose

## Como rodar

```bash
docker compose up -d
```

```bash
./gradlew bootRun
```

Para derrubar tudo:

```bash
docker compose down -v
```

A transportadora falsa fica em `localhost:8090` e a admin API dela em
`localhost:8090/__admin`.

## Endpoints

A cotação protegida, a mesma sem proteção nenhuma, e o vizinho saudável:

```bash
curl localhost:8080/quotes/SKU-1
curl -i localhost:8080/quotes/unprotected/SKU-1
curl localhost:8080/catalog/SKU-1
```

Quebrar a transportadora de formas diferentes:

```bash
curl -X POST localhost:8080/faults/healthy
curl -X POST 'localhost:8080/faults/slow?millis=3000'
curl -X POST localhost:8080/faults/server-error
curl -X POST localhost:8080/faults/connection-reset
curl -X POST localhost:8080/faults/not-found
curl -X POST 'localhost:8080/faults/fail-then-recover?failures=2'
```

Com a transportadora respondendo 500, a cotação protegida devolve 200 com
`"source":"FALLBACK"` e a sem proteção devolve 503:

```bash
curl -X POST localhost:8080/faults/server-error
curl localhost:8080/quotes/SKU-1
curl -i localhost:8080/quotes/unprotected/SKU-1
```

O registro de despacho, que não é idempotente — por isso existe a versão sem
retry:

```bash
curl -X POST localhost:8080/shipments \
  -H 'Content-Type: application/json' -d '{"sku":"SKU-1","quantity":2}'

curl -X POST 'localhost:8080/shipments?retry=false' \
  -H 'Content-Type: application/json' -d '{"sku":"SKU-1","quantity":2}'
```

Estado das políticas:

```bash
curl localhost:8080/actuator/circuitbreakers
curl localhost:8080/actuator/retryevents
curl localhost:8080/actuator/bulkheads
curl -s localhost:8080/actuator/prometheus | grep resilience4j
```

Os experimentos de onde vêm os números deste README:

```bash
curl -X POST localhost:8080/experiments/decorator-order
curl -X POST localhost:8080/experiments/bulkhead-isolation
curl -X POST localhost:8080/experiments/business-error
curl -X POST localhost:8080/experiments/slow-call
curl -X POST localhost:8080/experiments/minimum-calls
curl -X POST localhost:8080/experiments/open-circuit-retry
curl -X POST localhost:8080/experiments/time-limiter
curl -X POST localhost:8080/experiments/cancel-running-future
curl -X POST localhost:8080/experiments/retry-jitter
curl -X POST localhost:8080/experiments/non-idempotent-retry
curl -X POST localhost:8080/experiments/half-open-recovery
curl -X POST localhost:8080/experiments/fallback
```

## Setup das medições

Tudo numa única sessão, com o jar empacotado e o daemon do Gradle parado:

- `./gradlew clean bootJar` seguido de `./gradlew --stop`;
- `docker compose down -v && docker compose up -d`, infraestrutura limpa;
- `java -jar build/libs/fault-tolerance-1.0.0.jar`, nunca sob `bootRun`;
- 40 chamadas de aquecimento descartadas antes da primeira medição;
- JDK Corretto 21.0.5, WireMock 3.9.2 em container, tudo em `localhost`;
- Tomcat com `threads.max=50` e `accept-count=100`, declarados na configuração
  porque o experimento de bulkhead dimensiona a carga contra eles.

Medir sob `bootRun` com o daemon ativo dá resultados cerca de duas vezes mais
lentos e não comparáveis com os anteriores.

## A ordem em que os decoradores se compõem

As anotações do Resilience4j se compõem numa ordem fixa, de fora para dentro:
`Retry`, `CircuitBreaker`, `RateLimiter`, `TimeLimiter`, `Bulkhead`. Isso não é
detalhe: é o retry ficando **por fora** do circuit breaker, e portanto o breaker
registrando cada **tentativa**, não cada chamada lógica.

Cinco chamadas lógicas contra um serviço que responde 500, `maxAttempts = 3`,
janela de 20 com mínimo de 10 e limiar de 50%:

| Composição | Registradas pelo breaker | Estado | Recusadas | Requisições no serviço |
| --- | --- | --- | --- | --- |
| `Retry(CircuitBreaker(call))` | **10** | **OPEN** | 2 | **10** |
| `CircuitBreaker(Retry(call))` | 5 | CLOSED | 0 | **15** |

Com o retry por fora, a janela enche três vezes mais rápido: o circuito abre no
meio da quarta chamada lógica e as duas últimas falham sem sair da JVM. O custo
é que o breaker mede tentativas, não usuários — 50% de taxa de falha ali não
significa que metade dos clientes foi afetada.

Com o retry por dentro, o breaker é honesto sobre quantas chamadas lógicas
falharam, e por isso **nunca chega ao mínimo** num endpoint de baixo tráfego —
enquanto manda 50% mais requisições para um serviço que já está caindo.

Não existe escolha certa das duas. Existe a escolha que o `minimumNumberOfCalls`
precisa conhecer. A ordem é configurável por
`resilience4j.retry.retry-aspect-order` e pelas propriedades equivalentes das
outras políticas.

## O bulkhead é o que isola o resto da aplicação

150 chamadas concorrentes em `/quotes/...` contra uma transportadora que responde
em 1,5 s. Durante 3 segundos, uma sonda sequencial mede `/catalog/{sku}`, que não
chama nada:

| Carga | Respostas do vizinho em 3 s | p50 | p95 | máx |
| --- | --- | --- | --- | --- |
| sem bulkhead | **1** | 5871,8 ms | 5871,8 ms | 5871,8 ms |
| com bulkhead de 10 | **3066** | 0,84 ms | 1,93 ms | 11,6 ms |

É a falha mais comum em produção e a menos relacionada à dependência que falhou:
50 threads do Tomcat presas 1,5 s cada, mais 100 conexões na fila, e o endpoint
que não depende de nada para de existir.

O bulkhead de semáforo não acelera a transportadora. Ele diz que ela pode ocupar
10 threads, não 50, e devolve `BulkheadFullException` para o resto — que aqui
cai no fallback. A dependência continua lenta; a aplicação continua de pé.

`maxWaitDuration` transforma rejeição em fila. É quase sempre a escolha errada:
a espera queima a thread do chamador, que é exatamente o recurso que o bulkhead
foi colocado ali para proteger.

## Um 404 não é uma falha

A transportadora saudável respondendo 404 para SKUs que ela não cota. Vinte
consultas:

| Configuração | Falhas registradas | Estado | Recusadas pela aplicação |
| --- | --- | --- | --- |
| default ("toda exceção é falha") | 10 | **OPEN** | **10 de 20** |
| `recordExceptions` declarado | 0 | CLOSED | 0 |

Metade dos clientes recebeu um 503 da própria aplicação por causa de SKUs que
nunca existiram. É por isso que o cliente HTTP deste projeto não tem uma única
exceção: um 404 é `QuoteNotFoundException`, um 4xx qualquer é
`CarrierRejectedException`, e só 5xx, timeout de leitura e conexão derrubada
viram `CarrierUnavailableException`. Sem essa separação no cliente,
`recordExceptions` não tem o que listar.

No Actuator a distinção aparece como `kind="ignored"` ao lado de
`kind="failed"`.

## O serviço que nunca falha e derruba tudo

Uma dependência que responde 200 em 800 ms não produz uma única falha. Um breaker
que olha só a taxa de falha fica fechado para sempre, com todas as threads
paradas — a indisponibilidade que não parece uma:

| Detecção | Falhas | Chamadas lentas | Estado | p50 admitida |
| --- | --- | --- | --- | --- |
| só taxa de falha | 0 | 0 | **CLOSED** | 802,4 ms |
| `slowCallRateThreshold` 50% em 500 ms | 0 | **10** | **OPEN** | 802,3 ms |

O default de `slowCallDurationThreshold` é 60 segundos, o que na prática
significa "nunca". É uma propriedade que precisa ser escrita.

E o preço dos dois desfechos, na mesma rodada:

| Desfecho | Custo da chamada |
| --- | --- |
| admitida, serviço lento | **802 ms** |
| recusada pelo circuito aberto | **12,7 µs** (p95 25,2 µs) |

São 63 mil vezes. É isso que um circuito aberto compra: não a correção do
problema, mas a devolução imediata da thread.

## `minimumNumberOfCalls` é onde o circuito deixa de abrir

Trinta chamadas, todas falhando:

| Janela | Falhas registradas | Estado | Abriu na chamada |
| --- | --- | --- | --- |
| `COUNT_BASED` 100, mínimo 100 | 30 | **CLOSED** | nunca |
| `TIME_BASED` 10 s, mínimo 5 | 5 | **OPEN** | **5** |

A primeira configuração passa em revisão de código sem levantar suspeita, e
nunca abre num endpoint de baixo tráfego: antes de juntar 100 chamadas a janela
já deslizou. A segunda abre no mesmo tráfego — e o trade-off é o inverso, porque
ela também abre com três falhas às três da manhã.

## Retentar um circuito aberto

O default do retry é retentar **qualquer** exceção, e `CallNotPermittedException`
é uma exceção. Cinco chamadas contra um circuito aberto, backoff de 500 ms
dobrando:

| Configuração do retry | p50 | máx | Requisições no serviço |
| --- | --- | --- | --- |
| retenta qualquer coisa (default) | **1500,6 ms** | 1502,7 ms | 0 |
| `retryExceptions` como whitelist | **0,021 ms** | 0,061 ms | 0 |

Nenhuma das duas chega ao serviço — o circuito está aberto. A diferença é que a
primeira dorme 1,5 s para descobrir isso, por chamada, enquanto a dependência
está fora do ar e o tráfego inteiro está nesse caminho.

A correção é declarar o que vale retentar em vez de declarar o que não vale.
Nos eventos do Actuator o acerto fica visível como `IGNORED_ERROR` sobre
`CallNotPermittedException`.

## O time limiter limita a espera, não o trabalho

A mesma transportadora demorando 2 s, limite de 500 ms:

| Configuração | Tempo | Desfecho | Requisições servidas |
| --- | --- | --- | --- |
| sem limiter | **2003 ms** | respondeu | 1 |
| `@TimeLimiter` em retorno bloqueante | 1 ms | **IllegalReturnTypeException** | **0** |
| `@TimeLimiter` em `CompletableFuture` | **502 ms** | TimeoutException | **1** |

Duas coisas aqui.

A anotação em assinatura bloqueante não é inerte: o aspecto exige
`CompletionStage` e lança na primeira chamada. Compila, sobe, e falha em
produção com uma exceção da biblioteca.

E na assinatura assíncrona, onde funciona, a transportadora **serviu a
requisição**. O prazo devolveu o chamador em 502 ms; o trabalho do outro lado foi
feito e pago pelos dois. Quem fecha o socket é o read timeout do cliente HTTP, e
só ele.

### `cancelRunningFuture`

Transportadora em 2 s, limite de 500 ms, um único worker. Depois do timeout, uma
tarefa marcadora mede quando a thread volta a estar livre:

| `cancelRunningFuture` | Chamador liberado | Worker liberado depois disso | Requisições servidas |
| --- | --- | --- | --- |
| `true` | 501 ms | **1 ms** | 1 |
| `false` | 500 ms | **1503 ms** | 1 |

Com o cancelamento desligado, o prazo protege o chamador e deixa ocupado
exatamente o pool que ele deveria estar protegendo. Com ele ligado a thread
volta — e a transportadora serve a requisição nos dois casos. Interromper deste
lado não é cancelar do outro.

## Retry sem jitter não espalha carga, agenda carga

Vinte clientes falhando no mesmo instante, três tentativas cada, backoff de
200 ms dobrando. Medido no serviço: das 40 tentativas de retry, quantas caem na
mesma janela de 50 ms.

| `randomizationFactor` | Tentativas de retry | Pico numa janela de 50 ms | Dispersão |
| --- | --- | --- | --- |
| 0 (backoff exponencial puro) | 40 | **20** | 414 ms |
| 0,5 (backoff com jitter) | 40 | **9** | 711 ms |

Sem jitter, todos os clientes que falharam juntos esperam exatamente os mesmos
200 ms e voltam juntos: o serviço recebe o tráfego em ondas sincronizadas, que é
a pior forma de receber tráfego durante uma recuperação. O jitter dobra a
dispersão e corta o pico pela metade, pelo mesmo número de tentativas.

## Retry em operação não idempotente

Um retry não sabe distinguir "a requisição não chegou" de "a resposta não
voltou". A transportadora gravando o despacho e **então** falhando ao responder,
uma chamada lógica:

| Caminho | Despachos gravados do outro lado | Resposta ao chamador |
| --- | --- | --- |
| `@Retry` | **3** | CarrierUnavailableException |
| sem retry | **1** | CarrierUnavailableException |

O número vem do journal de requisições da própria transportadora, não da visão
da aplicação — que nos dois casos vê uma única chamada que falhou. Numa API de
cobrança, são três cobranças e um cliente que não entende o extrato.

Retry é seguro quando a operação é idempotente, e só então. A saída quando não é
costuma ser chave de idempotência — que é o projeto 51 deste portfólio.

## Half-open: quanto custa voltar

Circuito aberto, `waitDurationInOpenState` de 1 s, 3 sondas permitidas,
transição automática ligada. Transportadora curada no instante zero:

| Métrica | Valor |
| --- | --- |
| tempo até o circuito fechar | **1079 ms** |
| chamadas recusadas com o serviço já saudável | **50** |
| sondas enviadas | 3 |

A recuperação não é instantânea por projeto: o circuito prefere recusar 50
chamadas de um serviço saudável a arriscar reabrir cedo.

Quantas sondas permitir é a decisão interessante. Dez ciclos de recuperação
contra uma transportadora que falha **uma chamada em três**:

| Sondas permitidas | Fechou | Reabriu | Sondas enviadas ao serviço doente |
| --- | --- | --- | --- |
| 1 | 6 de 10 | **4** | 10 |
| 3 | **10 de 10** | 0 | **30** |

Com uma sonda, uma única requisição decide por todo mundo: quando ela é a
chamada ruim, o circuito volta a abrir e todos esperam outro ciclo. Com três, a
decisão passa a ser uma taxa — uma falha em três está abaixo do limiar de 50% e
o circuito fecha sempre. O preço são 30 requisições enviadas a um serviço que
pode ainda estar doente, em vez de 10.

## Onde o fallback é colocado

O fallback responde no lugar da falha, e por isso **apaga** a falha para tudo que
estiver acima dele. Transportadora que falha duas vezes e acerta na terceira:

| Posição do fallback | Fonte da resposta | Tentativas que chegaram ao serviço |
| --- | --- | --- |
| no `@Retry` (o mais externo) | **CARRIER** | **3** |
| no `@CircuitBreaker` (interno) | **FALLBACK** | **1** |

No decorador interno, o retry acima nunca dispara: o que ele recebe é um
fallback bem-sucedido. A política de retry continua configurada, continua
aparecendo em revisão, e não existe mais.

E o tipo do parâmetro do fallback decide o que ele engole. Com um
`NullPointerException` plantado no corpo do método, a transportadora saudável:

| Assinatura do fallback | Desfecho |
| --- | --- |
| `(String sku, Throwable t)` | **respondeu 200 com `source=FALLBACK`** |
| `(String sku, CarrierUnavailableException e)` | **NullPointerException propagado** |

O fallback largo transforma um bug do próprio código em resposta degradada: sai
do índice de erro, entra no índice de fallback, e ninguém tem alerta em cima
desse. O Resilience4j escolhe o fallback pela assinatura mais específica e deixa
propagar o que nenhuma aceita — então declarar a exceção que o fallback sabe
substituir é o que mantém o bug visível.

## Configuração

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 20
        minimum-number-of-calls: 10
        failure-rate-threshold: 50
        slow-call-duration-threshold: 2s
        slow-call-rate-threshold: 100
        wait-duration-in-open-state: 5s
        permitted-number-of-calls-in-half-open-state: 3
        automatic-transition-from-open-to-half-open-enabled: true
        record-exceptions:
          - com.gkcontas.resilience.carrier.CarrierUnavailableException
        ignore-exceptions:
          - com.gkcontas.resilience.carrier.QuoteNotFoundException
          - com.gkcontas.resilience.carrier.CarrierRejectedException
  retry:
    configs:
      default:
        max-attempts: 3
        wait-duration: 100ms
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2
        enable-randomized-wait: true
        randomized-wait-factor: 0.5
        retry-exceptions:
          - com.gkcontas.resilience.carrier.CarrierUnavailableException
  bulkhead:
    configs:
      default:
        max-concurrent-calls: 10
        max-wait-duration: 0
  timelimiter:
    configs:
      default:
        timeout-duration: 500ms
        cancel-running-future: true
```

Fora do Resilience4j, dois pontos que nenhuma política substitui:

```yaml
carrier:
  connect-timeout: 500ms
  read-timeout: 3s
```

São os únicos que fecham o socket. E o cliente é fixado em HTTP/1.1: o cliente do
JDK negocia HTTP/2 quando o servidor oferece, e aí todas as chamadas dividem uma
conexão multiplexada — um read timeout cancela o stream dele e mata as
requisições vizinhas que estavam em voo na mesma conexão.

## Cada política responde com o seu próprio status

| Situação | Status | `error` |
| --- | --- | --- |
| SKU sem cotação | 404 | `quote_not_found` |
| payload recusado pela transportadora | 422 | `carrier_rejected` |
| transportadora fora do ar | 503 | `carrier_unavailable` |
| circuito aberto | 503 + `Retry-After` | `circuit_open` |
| bulkhead cheio | 429 | `bulkhead_full` |
| prazo estourado | 504 | `carrier_timeout` |

O bulkhead cheio é 429 e não 503 de propósito: a dependência pode estar
perfeitamente saudável, e quem recusou a chamada foi a aplicação.

## Métricas

Uma série por política, com o desfecho como tag:

| Métrica | O que mostra |
| --- | --- |
| `resilience4j_circuitbreaker_state{state}` | um gauge por estado, 1 no atual |
| `resilience4j_circuitbreaker_calls_seconds_count{kind}` | `successful`, `failed`, `ignored` |
| `resilience4j_circuitbreaker_not_permitted_calls_total` | o que o circuito aberto recusou |
| `resilience4j_circuitbreaker_failure_rate` | a taxa que decide a abertura |
| `resilience4j_circuitbreaker_slow_call_rate` | a outra taxa que decide |
| `resilience4j_retry_calls_total{kind}` | com e sem retry, sucesso e falha |
| `resilience4j_bulkhead_available_concurrent_calls` | quanto do bulkhead sobrou |
| `resilience4j_timelimiter_calls_total{kind}` | `successful`, `failed`, `timeout` |

O `kind="ignored"` do circuit breaker é o que separa 404 de negócio de falha
técnica num painel. Sem ele, os dois são "chamada que não deu 200".

O estado também sai como detalhe de health em `/actuator/health`, e os eventos
crus em `/actuator/circuitbreakerevents` e `/actuator/retryevents`.

## Testes

79 testes, 42 sem infraestrutura nenhuma.

Os 42 são configuração pura: transições do circuit breaker, o que
`minimumNumberOfCalls` impede, chamada lenta contra chamada falha, as duas ordens
de composição com um `Supplier` contado, o que a whitelist do retry retenta, o
bulkhead soltando e enfileirando, os valores do backoff com e sem jitter.

Os 37 restantes sobem o WireMock em container — singleton em inicializador
estático, nunca `@Testcontainers`, porque o Spring reaproveita contextos entre
classes de teste. Eles cobrem a classificação das falhas de transporte, o
contrato HTTP de cada política, a ordem real em que as anotações se compõem, as
métricas publicadas e **cada um dos 12 experimentos** — a assertiva é a relação
que este README afirma, não o milissegundo, que depende da máquina. Se um
experimento parar de demonstrar o que diz, o build acusa.

O teste das métricas carrega `@AutoConfigureObservability`: o Spring Boot
desliga a exportação de métricas sob teste, e sem isso o endpoint de scrape
responde 404 e o teste passa afirmando nada.
