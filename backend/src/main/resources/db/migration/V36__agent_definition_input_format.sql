-- T05 评审修复：输入解析两种形态（结构化 json / 自然语言）在定义 input 约定中表达（04 决策 2）。
-- 一期两个业务 Agent 显式声明；其余（含未来新 Agent）默认自然语言。
ALTER TABLE app.agent_definitions
    ADD COLUMN input_format VARCHAR(16) NOT NULL DEFAULT 'NATURAL_LANGUAGE'
        CHECK (input_format IN ('STRUCTURED_JSON', 'NATURAL_LANGUAGE'));

UPDATE app.agent_definitions SET input_format = 'STRUCTURED_JSON'
    WHERE agent_slug = 'procurement-price-agent';
