-- 持久化每次 JD 出库建单尝试的实际 client mode，避免进程从 MOCK 切到 REAL 后
-- 把历史 Mock 结果误标为真实京东验收。旧记录无可靠来源，故 fail closed 保留 UNKNOWN。
ALTER TABLE app.shipment_jd_outbounds
    ADD COLUMN client_mode VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (client_mode IN ('UNKNOWN', 'MOCK', 'REAL'));
