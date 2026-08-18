-- Raw import rows are a current operator projection. Earlier review handling kept the original
-- SKU_MATCH error even after the SKU case was resolved while an order-level customer case remained.
WITH remaining AS (
    SELECT rir.id,
           COALESCE(
               (SELECT rc.reason_code
                FROM app.review_cases rc
                WHERE rc.order_id=rir.order_id
                  AND rc.order_line_id=rir.order_line_id
                  AND rc.status='OPEN'
                ORDER BY rc.created_at, rc.id
                LIMIT 1),
               (SELECT rc.reason_code
                FROM app.review_cases rc
                WHERE rc.order_id=rir.order_id
                  AND rc.order_line_id IS NULL
                  AND rc.status='OPEN'
                ORDER BY rc.created_at, rc.id
                LIMIT 1)
           ) review_reason
    FROM app.raw_import_rows rir
    WHERE rir.status='NEED_REVIEW'
)
UPDATE app.raw_import_rows rir
SET error_code=CASE remaining.review_reason
        WHEN 'CUSTOMER_MATCH_REQUIRED' THEN 'CUSTOMER_MATCH'
        WHEN 'SKU_MAPPING_REQUIRED' THEN 'SKU_MATCH'
        WHEN 'SKU_MAPPING_CONFLICT' THEN 'JD_CODE_CONFLICT'
        ELSE remaining.review_reason
    END,
    error_detail=jsonb_build_object('review_case_reason', remaining.review_reason),
    updated_at=CURRENT_TIMESTAMP
FROM remaining
WHERE rir.id=remaining.id AND remaining.review_reason IS NOT NULL;
