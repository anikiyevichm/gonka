# Локальная проверка smart contract gRPC allowlist

Эта инструкция описывает полный локальный прогон четырёх contract-facing gRPC routes на реальной Gonka network в Docker Desktop:

```text
smart-query JSON
  -> p0_probe.wasm
  -> QueryRequest::Grpc
  -> AcceptListGrpcQuerier
  -> native gRPC router/keeper
  -> protobuf response
  -> Rust decode
  -> JSON response контракта
```

Проверяемые разрешённые routes:

```text
/inference.inference.Query/GetCurrentEpoch
/inference.inference.Query/ListClaimRecipients
/inference.inference.Query/EpochPerformanceSummaryByParticipant
/inference.streamvesting.Query/TotalVestingAmount
```

Контрольный запрещённый route:

```text
/inference.inference.Query/EpochPerformanceSummaryAll
```

`p0-probe` — диагностическая test-only фикстура. Не загружайте её в public или production network.

## Что считается успешной проверкой

Прогон считается успешным, если одновременно выполнено следующее:

- Wasm fixture воспроизводимо собирается в pinned Docker image;
- checksum собранного файла совпадает с manifest и checksum события `store_code`;
- focused Go tests, полный `go test ./app`, `go vet` и focused race test проходят;
- chain и API images собраны из проверяемого commit;
- stateful Testermint test создаёт genesis, join participants и reward state;
- нода производит блоки и возвращает `catching_up=false`;
- в native state найдена существующая `EpochPerformanceSummary`;
- один экземпляр текущего `p0_probe.wasm` возвращает JSON для всех четырёх разрешённых queries;
- `EpochPerformanceSummaryAll` завершается ошибкой `path is not allowed from the contract`;
- сохранены commit, image IDs, artifact SHA-256, tx hashes, heights, code ID, contract address, payloads и raw responses.

Пустые `entries` и `total_amount` являются валидными transport/runtime responses. Для наиболее сильной evidence лучше получить непустой claim-recipient state. Для performance-summary обязательно использовать реально существующую запись: запрос несуществующей пары не доказывает положительный runtime path.

## 1. Требования

Для полного прогона нужны:

- Docker Desktop с Docker Engine, Compose v2 и buildx;
- JDK 21;
- Go версии, совместимой с модулями репозитория; для полного monorepo build рекомендуется Go 1.25.9 или новее;
- `make`, `jq`, `git`, `shasum`;
- доступ к интернету для первого скачивания base images, Gradle и Go dependencies;
- на Apple Silicon — поддержка запуска `linux/arm64` и `linux/amd64` containers.

Рекомендуемые ресурсы Docker Desktop:

```text
CPU:         8+
Memory:      16 GB
Swap:        4 GB
Free disk:   80 GB
```

Фактический проверенный прогон завершился и с примерно 8 GiB Docker RAM, но это не гарантирует стабильность повторной clean build.

## 2. Перейти в корень репозитория

Все дальнейшие команды, если не сказано обратное, выполняются из корня Gonka:

```bash
cd "$(git rev-parse --show-toplevel)"
git status --short --branch
git rev-parse HEAD
```

Запишите branch и полный commit. Не смешивайте в evidence результат, собранный из одного commit, с исходниками другого commit.

## 3. Подключиться именно к Docker Desktop

Testermint использует и `docker compose`, и Docker API из Java. Docker-compatible Podman backend для этого прогона не подходит.

На macOS сначала остановите Podman machine, если она запущена:

```bash
/opt/podman/bin/podman machine stop
```

Если стандартный `/var/run/docker.sock` принадлежит Docker Desktop:

```bash
unset DOCKER_HOST
unset CONTAINER_HOST
docker context use desktop-linux
```

Если `/var/run/docker.sock` всё ещё указывает на Podman, явно используйте socket Docker Desktop. Такой вариант был проверен на Apple Silicon:

```bash
export PATH="/Applications/Docker.app/Contents/Resources/bin:$PATH"
RUN_DOCKER_HOST="$(docker context inspect desktop-linux --format '{{(index .Endpoints "docker").Host}}')"
export DOCKER_HOST="$RUN_DOCKER_HOST"
unset CONTAINER_HOST
docker context use desktop-linux
```

Не выводите весь environment: в нём могут находиться токены. Проверяйте только нужные параметры.

Расширенный preflight:

```bash
docker version --format 'client={{.Client.Version}} server={{.Server.Version}} os={{.Server.Os}} arch={{.Server.Arch}}'
docker info --format 'name={{.Name}} os={{.OperatingSystem}} arch={{.Architecture}} cpus={{.NCPU}} memory={{.MemTotal}}'
docker context show
docker compose version
docker buildx version
docker buildx inspect --bootstrap
readlink /var/run/docker.sock || true
```

Критерии:

- server — Docker Desktop/Docker Engine, не Podman;
- OS не `fedora`;
- активный context — `desktop-linux`;
- `docker compose` и `docker buildx` работают;
- Java-процесс унаследует тот же `DOCKER_HOST`.

`make check-docker` недостаточно: он проверяет только успешность `docker info`, поэтому может дать false positive на Podman.

## 4. Настроить JDK и проверить toolchain

Пример для Homebrew OpenJDK 21 на Apple Silicon:

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"

java -version
go version
make --version
jq --version
cd testermint && ./gradlew --version && cd ..
```

Проверьте обе container architectures:

```bash
docker run --rm --platform linux/arm64 alpine uname -m
docker run --rm --platform linux/amd64 alpine uname -m
```

Ожидаются соответственно `aarch64` и `x86_64`. Chain images на Apple Silicon собираются нативно как arm64; pinned CosmWasm optimizer запускается как amd64 для воспроизводимости fixture.

## 5. Проверить порты, диск и старый state

Полный stack использует, среди прочих, порты:

```text
80-82, 1317, 8089-8092, 8101-8102, 8201-8202,
9000-9005, 9010-9015, 9020-9025, 9090-9091,
15432, 26656-26657
```

Минимальная проверка:

```bash
df -h /
docker system df
lsof -nP -iTCP -sTCP:LISTEN | grep -E ':(80|81|82|1317|8089|8090|8091|8092|9000|9010|9020|9090|9091|15432|26656|26657)[[:space:]]' || true
docker compose ls
docker ps -a --format '{{.Names}}\t{{.Status}}'
```

### Предупреждение о state

Stateful Testermint test выполняет reboot тестовой сети и пересоздаёт локальный chain state. `local-test-net/launch*.sh` также очищает `local-test-net/prod-local`. Перед продолжением сохраните нужные локальные данные и tx evidence. Не запускайте этот сценарий поверх ценной сети.

## 6. Воспроизводимо собрать и проверить Wasm fixture

```bash
cd inference-chain/contracts/p0-probe

./build.sh verify
make build CONTAINER_ENGINE=docker
make check CONTAINER_ENGINE=docker
shasum -a 256 artifacts/p0_probe.wasm
stat -f 'size_bytes=%z' artifacts/p0_probe.wasm
git diff -- artifacts/p0_probe.wasm artifacts/checksums.txt

cd ../../..
```

Для commit `042758f4aa911606d34fc90fc1f3f257c09d55b8` ожидается:

```text
size:   272814 bytes
sha256: 7eacebc656412fe59d290ecac1a0608686372c39ae6d63a8cb7fcba23417a91d
```

Если fixture изменена более новым commit, источником истины является актуальный `artifacts/checksums.txt`, а не приведённый выше исторический checksum. После clean rebuild diff artifact и manifest должен быть пустым.

## 7. Запустить Go regression

```bash
cd inference-chain

go test ./app -run '^(TestAcceptedGrpcQueries|TestAcceptedStargateQueriesUnchanged|TestWasmGrpcForwardMarketplaceQueryAllowlist)$' -count=1
go test ./app -count=1
go vet ./app
go test -race ./app -run '^TestWasmGrpcForwardMarketplaceQueryAllowlist$' -count=1

cd ..
git diff --check
git diff --cached --check
```

На macOS race build может вывести linker warning про malformed `LC_DYSYMTAB`. Если команда завершается с exit code `0` и тест имеет статус `ok`, это предупреждение не является падением теста.

## 8. Собрать Docker images

### Канонический вариант

Для платформы, на которой все targets собираются штатно:

```bash
make check-docker
make build-docker \
  GENESIS_OVERRIDES_FILE=inference-chain/test_genesis_overrides.json
```

Полный target собирает больше образов, чем нужно focused smart-contract прогону, поэтому требует заметно больше времени и диска.

### Focused Apple Silicon build

Для stateful сценария нужны chain, DAPI, mock-server, proxy и edge-api. На Apple Silicon:

```bash
make node-build-docker \
  GENESIS_OVERRIDES_FILE=inference-chain/test_genesis_overrides.json \
  DOCKER_PLATFORM=linux/arm64 \
  DOCKER_GOOS=linux \
  DOCKER_GOARCH=arm64 \
  BLST_PORTABLE=1

make mock-server-build-docker DOCKER_PLATFORM=linux/arm64
make proxy-build-docker DOCKER_PLATFORM=linux/arm64
make edge-api-build-docker \
  DOCKER_PLATFORM=linux/arm64 \
  DOCKER_GOOS=linux \
  DOCKER_GOARCH=arm64 \
  BLST_PORTABLE=1
```

На момент проверенного прогона `decentralized-api/Makefile` внутри `build-docker` безусловно переопределял platform на `linux/amd64`. Под amd64 emulation Go compiler падал с `SIGSEGV` в Envoy assembler. До исправления Makefile DAPI можно собрать тем же Dockerfile напрямую как arm64:

```bash
RUN_VERSION="$(git describe --always)"
RUN_COMMIT="$(git rev-parse HEAD)"
RUN_LDFLAGS="-X github.com/cosmos/cosmos-sdk/version.Name=decentralized-api -X github.com/cosmos/cosmos-sdk/version.AppName=decentralized-api -X github.com/cosmos/cosmos-sdk/version.Version=${RUN_VERSION} -X github.com/cosmos/cosmos-sdk/version.Commit=${RUN_COMMIT}"

docker build \
  --platform linux/arm64 \
  --build-arg LDFLAGS="$RUN_LDFLAGS" \
  --build-arg GOOS=linux \
  --build-arg GOARCH=arm64 \
  --build-arg BLST_PORTABLE=1 \
  --build-arg DEVSHARD_VERSION="$RUN_VERSION" \
  -f decentralized-api/Dockerfile . \
  -t "ghcr.io/product-science/api:${RUN_VERSION}"

docker tag \
  "ghcr.io/product-science/api:${RUN_VERSION}" \
  ghcr.io/product-science/api:latest
```

Проверьте architecture и сохраните image IDs:

```bash
docker image inspect \
  ghcr.io/product-science/inferenced:latest \
  ghcr.io/product-science/api:latest \
  ghcr.io/product-science/edge-api:latest \
  ghcr.io/product-science/proxy:latest \
  inference-mock-server:latest \
  --format '{{index .RepoTags 0}} id={{.Id}} os={{.Os}} arch={{.Architecture}} size={{.Size}}'
```

Все пять образов на Apple Silicon должны иметь `arch=arm64`.

Если build завершается transient ошибкой `unexpected EOF` при скачивании Go module, повторите ту же команду: успешно загруженные layers останутся в BuildKit cache. Если disk заполнен, сначала изучите `docker system df`; не удаляйте images или volumes, содержащие нужный state, вслепую.

## 9. Запустить stateful Testermint scenario

Из корня репозитория:

```bash
make run-tests \
  TESTS='ClaimRecipientTests.claim rewards can be routed to configured recipient'
```

Эквивалентная прямая форма:

```bash
cd testermint
./gradlew :test \
  --tests 'ClaimRecipientTests.claim rewards can be routed to configured recipient' \
  -DexcludeTags=unstable,exclude
cd ..
```

Не прерывайте процесс во время PoC или epoch transition. Сценарий может занять 10-20 минут. В проверенном прогоне он завершился за `8m 29s`.

В соседнем terminal можно наблюдать состояние:

```bash
docker ps --format '{{.Names}}\t{{.Image}}\t{{.Status}}' | sort
docker network inspect chain-public
docker exec genesis-node inferenced status 2>/dev/null | \
  jq '{height:.sync_info.latest_block_height,time:.sync_info.latest_block_time,catching_up:.sync_info.catching_up}'
```

Критерии:

- Gradle выводит `BUILD SUCCESSFUL`;
- существуют genesis, join1, join2 и `test-dns`;
- три PostgreSQL-контейнера healthy;
- block height растёт;
- `catching_up=false`.

Сценарий намеренно останавливает API одного participant перед manual claim. Поэтому `join1-api` в состоянии `Exited (137)` после успешного теста допустим; chain smart queries выполняются через `genesis-node`.

## 10. Найти реальную performance summary

Не подставляйте текущую эпоху наугад. Сначала получите native list:

```bash
docker exec genesis-node inferenced query inference \
  list-epoch-performance-summary --output json | \
  jq '.epochPerformanceSummary'
```

Выберите существующую запись, предпочтительно с `claimed=true`, и сохраните пару:

```bash
NATIVE_SUMMARIES="$(docker exec genesis-node inferenced query inference list-epoch-performance-summary --output json)"
EPOCH_INDEX="$(printf '%s' "$NATIVE_SUMMARIES" | jq -r '.epochPerformanceSummary[] | select(.claimed == true) | .epoch_index' | head -n 1)"
PARTICIPANT_ID="$(printf '%s' "$NATIVE_SUMMARIES" | jq -r '.epochPerformanceSummary[] | select(.claimed == true) | .participant_id' | head -n 1)"

test -n "$EPOCH_INDEX"
test -n "$PARTICIPANT_ID"

printf 'epoch=%s participant=%s\n' "$EPOCH_INDEX" "$PARTICIPANT_ID"
```

Подтвердите точечный native query:

```bash
docker exec genesis-node inferenced query inference \
  show-epoch-performance-summary-by-participant \
  "$EPOCH_INDEX" "$PARTICIPANT_ID" --output json | jq .
```

Дополнительно проверьте claim-recipient и vesting state:

```bash
docker exec genesis-node inferenced query inference \
  list-claim-recipients "$PARTICIPANT_ID" --output json | jq .

docker exec genesis-node inferenced query streamvesting \
  total-vesting "$PARTICIPANT_ID" --output json | jq .
```

Claim-recipient override может исчезнуть после прохождения назначенной эпохи. Поэтому снимайте positive evidence сразу после завершения Testermint test. Если response уже пуст, route всё равно можно проверить пустым успешным ответом либо создать новый future override стандартной транзакцией и дождаться её включения в блок.

## 11. Загрузить текущий Wasm

Сначала скопируйте artifact в genesis container и повторно сверьте hash:

```bash
docker cp \
  inference-chain/contracts/p0-probe/artifacts/p0_probe.wasm \
  genesis-node:/tmp/p0_probe.wasm

LOCAL_WASM_SHA="$(shasum -a 256 inference-chain/contracts/p0-probe/artifacts/p0_probe.wasm | awk '{print $1}')"
CONTAINER_WASM_SHA="$(docker exec genesis-node sha256sum /tmp/p0_probe.wasm | awk '{print $1}')"

printf 'local=%s\ncontainer=%s\n' "$LOCAL_WASM_SHA" "$CONTAINER_WASM_SHA"
test "$LOCAL_WASM_SHA" = "$CONTAINER_WASM_SHA"
```

Store transaction:

```bash
STORE_RESULT="$(docker exec genesis-node inferenced tx wasm store /tmp/p0_probe.wasm \
  --from genesis \
  --keyring-backend test \
  --gas auto \
  --gas-adjustment 1.3 \
  --broadcast-mode sync \
  --output json \
  --yes)"

STORE_TX_HASH="$(printf '%s' "$STORE_RESULT" | jq -r '.txhash')"
printf 'store_tx=%s\n' "$STORE_TX_HASH"
test -n "$STORE_TX_HASH"
```

Подождите 2-5 секунд и запросите tx. Не сохраняйте полный store tx JSON в публичный отчёт без фильтрации: он содержит полный Wasm bytecode и создаёт огромный шум.

```bash
sleep 3
docker exec genesis-node inferenced query tx "$STORE_TX_HASH" --output json | \
  jq '{height,code,store_code_events:[.events[]? | select(.type == "store_code")]}'
```

Извлеките `code_id` и checksum:

```bash
STORE_TX="$(docker exec genesis-node inferenced query tx "$STORE_TX_HASH" --output json)"
STORE_CODE="$(printf '%s' "$STORE_TX" | jq -r '.code')"
CODE_ID="$(printf '%s' "$STORE_TX" | jq -r '.events[] | select(.type == "store_code") | .attributes[] | select(.key == "code_id") | .value')"
EVENT_WASM_SHA="$(printf '%s' "$STORE_TX" | jq -r '.events[] | select(.type == "store_code") | .attributes[] | select(.key == "code_checksum") | .value')"

test "$STORE_CODE" = "0"
test "$EVENT_WASM_SHA" = "$LOCAL_WASM_SHA"
printf 'code_id=%s checksum=%s\n' "$CODE_ID" "$EVENT_WASM_SHA"
```

Если tx ещё не найдена, подождите следующий блок и повторите query.

## 12. Instantiate без admin

```bash
INSTANTIATE_RESULT="$(docker exec genesis-node inferenced tx wasm instantiate "$CODE_ID" '{}' \
  --label p0-grpc-docker-live \
  --no-admin \
  --from genesis \
  --keyring-backend test \
  --gas auto \
  --gas-adjustment 1.3 \
  --broadcast-mode sync \
  --output json \
  --yes)"

INSTANTIATE_TX_HASH="$(printf '%s' "$INSTANTIATE_RESULT" | jq -r '.txhash')"
printf 'instantiate_tx=%s\n' "$INSTANTIATE_TX_HASH"
test -n "$INSTANTIATE_TX_HASH"
```

После включения tx в блок:

```bash
sleep 3
INSTANTIATE_TX="$(docker exec genesis-node inferenced query tx "$INSTANTIATE_TX_HASH" --output json)"
INSTANTIATE_CODE="$(printf '%s' "$INSTANTIATE_TX" | jq -r '.code')"
CONTRACT_ADDRESS="$(printf '%s' "$INSTANTIATE_TX" | jq -r '.events[] | select(.type == "instantiate") | .attributes[] | select(.key == "_contract_address") | .value')"

test "$INSTANTIATE_CODE" = "0"
test -n "$CONTRACT_ADDRESS"
printf 'contract=%s\n' "$CONTRACT_ADDRESS"
```

Подтвердите code ID, label и отсутствие admin:

```bash
docker exec genesis-node inferenced query wasm contract \
  "$CONTRACT_ADDRESS" --output json | \
  jq '{address,contract_info:{code_id:.contract_info.code_id,creator:.contract_info.creator,admin:.contract_info.admin,label:.contract_info.label}}'
```

## 13. Выполнить четыре разрешённых smart queries

Все четыре queries должны выполняться через один `CONTRACT_ADDRESS`.

### 13.1 GetCurrentEpoch

```bash
QUERY_CURRENT_EPOCH='{"get_current_epoch":{}}'

docker exec genesis-node inferenced query wasm contract-state smart \
  "$CONTRACT_ADDRESS" "$QUERY_CURRENT_EPOCH" --output json | jq .
```

Ожидаемый shape:

```json
{"data":{"epoch":8}}
```

Значение epoch зависит от момента запуска.

### 13.2 ListClaimRecipients

```bash
QUERY_CLAIM_RECIPIENTS="$(jq -nc \
  --arg participant "$PARTICIPANT_ID" \
  '{list_claim_recipients:{participant:$participant}}')"

docker exec genesis-node inferenced query wasm contract-state smart \
  "$CONTRACT_ADDRESS" "$QUERY_CLAIM_RECIPIENTS" --output json | jq .
```

Успешный непустой response имеет форму:

```json
{
  "data": {
    "entries": [
      {"epoch": 4, "recipient": "gonka1..."}
    ]
  }
}
```

`{"data":{"entries":[]}}` также доказывает route/codec path, но в evidence надо явно отметить отсутствие актуального override.

### 13.3 EpochPerformanceSummaryByParticipant

```bash
QUERY_PERFORMANCE="$(jq -nc \
  --argjson epoch_index "$EPOCH_INDEX" \
  --arg participant_id "$PARTICIPANT_ID" \
  '{epoch_performance_summary:{epoch_index:$epoch_index,participant_id:$participant_id}}')"

docker exec genesis-node inferenced query wasm contract-state smart \
  "$CONTRACT_ADDRESS" "$QUERY_PERFORMANCE" --output json | jq .
```

Ожидаемый shape для существующей записи:

```json
{
  "data": {
    "epoch_index": 4,
    "participant_id": "gonka1...",
    "earned_coins": 0,
    "rewarded_coins": 94864721408887,
    "claimed": true
  }
}
```

Числа и `claimed` зависят от state. Критично, чтобы JSON соответствовал выбранной native summary.

### 13.4 TotalVestingAmount

```bash
QUERY_TOTAL_VESTING="$(jq -nc \
  --arg participant_address "$PARTICIPANT_ID" \
  '{total_vesting:{participant_address:$participant_address}}')"

docker exec genesis-node inferenced query wasm contract-state smart \
  "$CONTRACT_ADDRESS" "$QUERY_TOTAL_VESTING" --output json | jq .
```

Для аккаунта без vesting schedule ожидается:

```json
{"data":{"total_amount":[]}}
```

Непустой schedule имеет форму:

```json
{"data":{"total_amount":[{"denom":"ngonka","amount":"123"}]}}
```

Оба результата являются успешной проверкой route и protobuf decode.

## 14. Проверить denied boundary

Probe специально имеет `raw_grpc` только для adversarial проверки allowlist:

```bash
QUERY_DENIED='{"raw_grpc":{"path":"/inference.inference.Query/EpochPerformanceSummaryAll","data":""}}'

set +e
DENIED_OUTPUT="$(docker exec genesis-node inferenced query wasm contract-state smart \
  "$CONTRACT_ADDRESS" "$QUERY_DENIED" --output json 2>&1)"
denied_exit_code=$?
set -e

printf '%s\n' "$DENIED_OUTPUT"
printf 'exit_code=%s\n' "$denied_exit_code"

test "$denied_exit_code" -ne 0
printf '%s\n' "$DENIED_OUTPUT" | \
  grep -F "'/inference.inference.Query/EpochPerformanceSummaryAll' path is not allowed from the contract"
```

Не используйте имя `status` для exit code в zsh: это зарезервированный shell parameter.

Ожидаемый смысл ошибки:

```text
Unsupported query type: '/inference.inference.Query/EpochPerformanceSummaryAll'
path is not allowed from the contract
```

Если broad route неожиданно возвращает успешный JSON, проверка провалена: allowlist стал шире ожидаемого.

## 15. Сохранить evidence

Минимально сохраните:

```bash
date '+local_time=%Y-%m-%dT%H:%M:%S%z'
git status --short --branch
git rev-parse HEAD
docker version --format 'client={{.Client.Version}} server={{.Server.Version}} os={{.Server.Os}} arch={{.Server.Arch}}'
docker context show
docker compose version
docker buildx version
docker image inspect ghcr.io/product-science/inferenced:latest --format 'id={{.Id}} os={{.Os}} arch={{.Architecture}} size={{.Size}}'
shasum -a 256 inference-chain/contracts/p0-probe/artifacts/p0_probe.wasm
docker ps --format '{{.Names}}\t{{.Image}}\t{{.Status}}' | sort
docker exec genesis-node inferenced status 2>/dev/null | jq '.sync_info'
```

В отчёт также входят:

- stdout и exit code deterministic Rust/Go checks;
- Testermint test name, duration и `BUILD SUCCESSFUL`;
- native performance-summary record;
- store tx hash, height, code ID и checksum;
- instantiate tx hash, height, contract address и подтверждение пустого admin;
- четыре exact payloads и четыре raw successful responses;
- denied payload, ненулевой exit code и raw error;
- объяснение любого infrastructure workaround.

Не включайте в evidence seed phrases, private keys, keyring contents, passwords, registry tokens, полный environment dump или полный store transaction с Wasm bytecode.

## 16. Что делать после проверки

Если сеть нужна для дополнительного анализа, оставьте её запущенной и сохраните contract address и tx hashes. Перед очисткой сначала посмотрите точные compose projects и volumes:

```bash
docker compose ls
docker ps -a --format '{{.Names}}\t{{.Status}}'
docker volume ls
docker system df
```

Удаление Testermint projects, containers и volumes уничтожит локальный chain state. Выполняйте cleanup только после сохранения evidence и только для явно выбранных genesis/join test projects.

После push дополнительно дождитесь зелёного remote GitHub Actions job:

```text
Reproduce P0 Wasm fixture
```

Локальный PASS без этого job доказывает runtime behavior на проверенной машине, но не заменяет каноническую remote reproducibility evidence.

## Troubleshooting

### Docker CLI всё ещё показывает Podman

Признаки: server OS `fedora`, в socket path есть `podman`, отсутствуют `docker compose` или `docker buildx`.

Решение: остановить Podman, запустить Docker Desktop, переключить context и явно задать Docker Desktop socket, как описано в шаге 3.

### Testermint не видит Docker, хотя CLI работает

Убедитесь, что `DOCKER_HOST` экспортирован, а не задан только перед одной CLI-командой. Gradle/Java process должен унаследовать его.

### DAPI падает при amd64 build на Apple Silicon

Используйте focused native arm64 build из шага 8. Это известное ограничение текущего DAPI Makefile/emulation path, а не ошибка smart-contract allowlist.

### `query tx` сообщает, что транзакция не найдена

Broadcast mode `sync` возвращает до включения tx в блок. Подождите несколько секунд и повторите query. Не принимайте наличие tx hash за успешное выполнение: итоговый `code` обязан быть `0`.

### Performance query возвращает `unknown request`

Сначала подтвердите точную пару `epoch_index + participant_id` через native list и participant-scoped query. Не используйте несуществующую или текущую эпоху наугад.

### Claim-recipient response стал пустым

Scheduled override может быть удалён после наступления соответствующей эпохи. Снимайте evidence сразу после Testermint test. Пустой typed response всё равно доказывает transport path; для непустой business-state evidence создайте новую future override и дождитесь блока.

### `TotalVestingAmount` возвращает пустой массив

Это нормальное состояние аккаунта без vesting schedule. Проверка успешна, если request прошёл allowlist и контракт корректно декодировал native response.

### На диске заканчивается место

Сначала выполните `docker system df`. BuildKit cache можно очищать отдельно от images и volumes, но не удаляйте нужные образы или state без проверки targets. После очистки cache следующая build снова скачает зависимости.

## Проверенный эталонный результат

Локальный прогон 2026-09-08 на commit `042758f4aa911606d34fc90fc1f3f257c09d55b8` дал:

```text
Wasm SHA-256:
7eacebc656412fe59d290ecac1a0608686372c39ae6d63a8cb7fcba23417a91d

store tx:
44D6EE83C4DA648A7DB9C0211C3CBF63211B823DCF7F1B50CBD3E4AE9800CBF5

instantiate tx:
57FC11EF5DE0A774A6AAE1A1CBD23D8F6E3624EEBDB87BB8C67C50F6DB35419B

code_id: 1
contract:
gonka14hj2tavq8fpesdwxxcu44rty3hh90vhujrvcmstl4zr3txmfvw9sjhejf8

Testermint:
BUILD SUCCESSFUL in 8m 29s
```

Через этот контракт прошли все четыре разрешённых queries, включая существующую epoch performance summary, а `EpochPerformanceSummaryAll` получил ожидаемый allowlist denial.
