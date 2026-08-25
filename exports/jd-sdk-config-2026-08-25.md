# 京东 ISC/LOP SDK 配置快照（脱敏）

生成日期：2026-08-25

## 配置来源

- SDK 凭据：`backend/.env.jd.uat.local`（本地 `0600`、git-ignored，未复制密钥值）
- 应用映射：`backend/src/main/resources/application.yml` 的 `app.jd`
- 运行门闩：根目录 `.env` 与 `docker-compose.yml`
- 默认只读探针：`scripts/jd-readonly-uat.sh`

## 当前有效运行配置

```dotenv
JD_LOP_ENV=UAT
JD_LOP_CLIENT_MODE=REAL
JD_LOP_WRITE_MODE=ON
JD_GENERIC_HTTP_WRITE_MODE=OFF
JD_OUTBOUND_AUTHORIZED_OPERATORS=zimu-admin
JD_TRACKING_BACKFILL_ENABLED=true
JD_TRACKING_BACKFILL_POLL_MS=60000
JD_TRACKING_BACKFILL_BATCH_SIZE=20
JD_TRACKING_BACKFILL_MIN_INTERVAL=PT1M
```

## 已配置的 SDK 凭据键（值已脱敏）

```dotenv
JD_LOP_SERVER_URL=<REDACTED>
JD_LOP_APP_KEY=<REDACTED>
JD_LOP_APP_SECRET=<REDACTED>
JD_LOP_ACCESS_TOKEN=<REDACTED>
JD_LOP_REFRESH_TOKEN=<REDACTED>
JD_LOP_PIN=<REDACTED>
JD_LOP_OWNER_NO=<REDACTED>
```

## 重要说明

- 该文件是可分享的脱敏快照，不能直接作为真实 SDK 环境文件使用。
- 原始凭据仍保留在 `backend/.env.jd.uat.local`，未被改写、复制或打印。
- 当前 `JD_LOP_WRITE_MODE=ON`，表示受控写门闩已打开；本次仅导出配置，没有发起京东写请求。
