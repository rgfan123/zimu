-- V8-V19 allowed free-form model/SDK errors. Before those rows are served by the
-- management API, collapse errors to stable codes and rebuild message-submission
-- ReviewCase detail from an explicit allowlist.

UPDATE app.async_tasks
SET last_error = CASE
        WHEN last_error IS NULL OR btrim(last_error) = '' THEN NULL
        WHEN btrim(last_error) IN
             ('MODEL_NOT_CONFIGURED', 'MODEL_CALL_FAILED', 'MODEL_OUTPUT_INVALID')
            THEN btrim(last_error)
        ELSE 'MODEL_CALL_FAILED'
    END,
    updated_at = CURRENT_TIMESTAMP
WHERE task_type = 'INTERPRET_MESSAGE'
  AND last_error IS DISTINCT FROM CASE
        WHEN last_error IS NULL OR btrim(last_error) = '' THEN NULL
        WHEN btrim(last_error) IN
             ('MODEL_NOT_CONFIGURED', 'MODEL_CALL_FAILED', 'MODEL_OUTPUT_INVALID')
            THEN btrim(last_error)
        ELSE 'MODEL_CALL_FAILED'
    END;

UPDATE app.message_interpretations
SET error = CASE
        WHEN error IS NULL OR btrim(error) = '' THEN NULL
        WHEN btrim(error) IN
             ('MODEL_NOT_CONFIGURED', 'MODEL_CALL_FAILED', 'MODEL_OUTPUT_INVALID')
            THEN btrim(error)
        ELSE 'MODEL_CALL_FAILED'
    END
WHERE error IS DISTINCT FROM CASE
        WHEN error IS NULL OR btrim(error) = '' THEN NULL
        WHEN btrim(error) IN
             ('MODEL_NOT_CONFIGURED', 'MODEL_CALL_FAILED', 'MODEL_OUTPUT_INVALID')
            THEN btrim(error)
        ELSE 'MODEL_CALL_FAILED'
    END;

WITH review_history AS (
    SELECT rc.id,
           rc.detail,
           COALESCE(
               NULLIF(btrim(rc.detail ->> 'error_code'), ''),
               NULLIF(btrim(rc.detail ->> 'error'), ''),
               NULLIF(btrim(rc.detail ->> 'last_error'), ''),
               NULLIF(btrim(rc.detail ->> 'error_message'), ''),
               NULLIF(btrim(rc.detail ->> 'exception_message'), '')
           ) AS error_candidate,
           (
               SELECT COALESCE(jsonb_agg(entry.item ORDER BY entry.ordinal), '[]'::jsonb)
               FROM jsonb_array_elements(
                   CASE
                       WHEN jsonb_typeof(rc.detail #> '{model_output,names}') = 'array'
                           THEN rc.detail #> '{model_output,names}'
                       ELSE '[]'::jsonb
                   END
               ) WITH ORDINALITY AS entry(item, ordinal)
               WHERE entry.ordinal <= 100
                 AND jsonb_typeof(entry.item) IN ('string', 'number', 'boolean')
                 AND char_length(btrim(entry.item #>> '{}')) BETWEEN 1 AND 256
           ) AS names,
           (
               SELECT COALESCE(jsonb_agg(entry.item ORDER BY entry.ordinal), '[]'::jsonb)
               FROM jsonb_array_elements(
                   CASE
                       WHEN jsonb_typeof(rc.detail #> '{model_output,tracking_nos}') = 'array'
                           THEN rc.detail #> '{model_output,tracking_nos}'
                       ELSE '[]'::jsonb
                   END
               ) WITH ORDINALITY AS entry(item, ordinal)
               WHERE entry.ordinal <= 100
                 AND jsonb_typeof(entry.item) IN ('string', 'number', 'boolean')
                 AND char_length(btrim(entry.item #>> '{}')) BETWEEN 1 AND 256
           ) AS tracking_nos
    FROM app.review_cases rc
    WHERE rc.message_submission_id IS NOT NULL
      AND rc.reason_code IN ('WECOM_NEED_REVIEW', 'WECOM_ORDER_CHANGE', 'WECOM_ORDER_CANCEL')
), sanitized AS (
    SELECT id,
           '{}'::jsonb
           || CASE
                WHEN detail ->> 'intent' IN
                     ('CUSTOMER_ORDER', 'SUPPLIER_TRACKING', 'ORDER_CHANGE',
                      'ORDER_CANCEL', 'NON_BUSINESS', 'NEED_REVIEW')
                    THEN jsonb_build_object('intent', detail ->> 'intent')
                ELSE '{}'::jsonb
              END
           || CASE
                WHEN detail ->> 'provider' ~ '^[A-Za-z0-9._:/@+-]{1,128}$'
                    THEN jsonb_build_object('provider', detail ->> 'provider')
                ELSE '{}'::jsonb
              END
           || CASE
                WHEN detail ->> 'model' ~ '^[A-Za-z0-9._:/@+-]{1,128}$'
                    THEN jsonb_build_object('model', detail ->> 'model')
                ELSE '{}'::jsonb
              END
           || CASE
                WHEN detail ->> 'prompt_version' ~ '^[A-Za-z0-9._:/@+-]{1,64}$'
                    THEN jsonb_build_object('prompt_version', detail ->> 'prompt_version')
                ELSE '{}'::jsonb
              END
           || CASE
                WHEN error_candidate IS NOT NULL THEN jsonb_build_object(
                    'error_code',
                    CASE
                        WHEN error_candidate IN
                             ('MODEL_NOT_CONFIGURED', 'MODEL_CALL_FAILED', 'MODEL_OUTPUT_INVALID')
                            THEN error_candidate
                        ELSE 'MODEL_CALL_FAILED'
                    END)
                ELSE '{}'::jsonb
              END
           || CASE
                WHEN char_length(btrim(detail ->> 'order_no')) BETWEEN 3 AND 100
                 AND btrim(detail ->> 'order_no') ~ '^[A-Za-z0-9]+([._/-][A-Za-z0-9]+)*$'
                 AND btrim(detail ->> 'order_no') !~ '^1[3-9][0-9]{9}$'
                    THEN jsonb_build_object('order_no', btrim(detail ->> 'order_no'))
                ELSE '{}'::jsonb
              END
           || CASE
                WHEN detail ->> 'reason' = 'LINE_PAIRING_UNRESOLVED' THEN
                    jsonb_build_object(
                        'reason', 'LINE_PAIRING_UNRESOLVED',
                        'message', '批量运单无法建立逐行姓名—运单号对应关系，系统不按两个列表的位置猜测配对')
                    || CASE
                         WHEN jsonb_array_length(names) > 0 OR jsonb_array_length(tracking_nos) > 0
                             THEN jsonb_build_object(
                                 'model_output',
                                 '{}'::jsonb
                                 || CASE WHEN jsonb_array_length(names) > 0
                                      THEN jsonb_build_object('names', names)
                                      ELSE '{}'::jsonb END
                                 || CASE WHEN jsonb_array_length(tracking_nos) > 0
                                      THEN jsonb_build_object('tracking_nos', tracking_nos)
                                      ELSE '{}'::jsonb END)
                         ELSE '{}'::jsonb
                       END
                ELSE '{}'::jsonb
              END AS safe_detail
    FROM review_history
)
UPDATE app.review_cases rc
SET detail = sanitized.safe_detail,
    updated_at = CURRENT_TIMESTAMP
FROM sanitized
WHERE rc.id = sanitized.id
  AND rc.detail IS DISTINCT FROM sanitized.safe_detail;

COMMENT ON COLUMN app.async_tasks.last_error IS
    'INTERPRET_MESSAGE rows use stable public failure codes; V20 normalizes older free-form values.';
COMMENT ON COLUMN app.message_interpretations.error IS
    'Stable message interpretation failure code; provider and SDK exception text is never public data.';
