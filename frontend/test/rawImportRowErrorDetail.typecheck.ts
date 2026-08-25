import type { RawImportRow, RawImportRowErrorDetail } from '../src/api/types';

type Equal<Left, Right> =
  (<Value>() => Value extends Left ? 1 : 2) extends
  (<Value>() => Value extends Right ? 1 : 2)
    ? true
    : false;
type Expect<Value extends true> = Value;

/**
 * 本文件守的是一个真实发生过的缺陷：
 * 后端 `SourceImportService:202-204` 写复数数组 `order_line_exceptions`，
 * 前端读单数字符串 `order_line_exception`，键名与类型同时对不上 ——
 * 该分支恒为空，无声退化到粗粒度 `error_code` 文案，编译期毫无察觉。
 * `error_detail` 当时的类型是 `Record<string, unknown>`，任何键名都合法。
 */

const detail: RawImportRowErrorDetail = {};

// @ts-expect-error 单数 order_line_exception 是后端从不写的键名；这条 @ts-expect-error
// 一旦「未被使用」而报错，说明有人给 RawImportRowErrorDetail 加了索引签名，
// 把拼错键名重新变成合法写法 —— 那正是要拦住的回退。
void detail.order_line_exception;

/**
 * 值必须保持 `unknown`：线上 JSON 不可信，且三个写入方各写各的形状。
 * 若把 order_line_exceptions 标成 `string[]`，读取处的
 * `Array.isArray()` / `typeof code === 'string'` 守卫会显得多余而被后人删掉。
 */
export type MessageStaysUnknown = Expect<
  Equal<RawImportRowErrorDetail['message'], unknown>
>;
export type LineExceptionsStayUnknown = Expect<
  Equal<RawImportRowErrorDetail['order_line_exceptions'], unknown>
>;
export type ReviewCaseReasonStaysUnknown = Expect<
  Equal<RawImportRowErrorDetail['review_case_reason'], unknown>
>;

/** RawImportRow 必须用上这个类型，而不是退回裸 Record。 */
export type RawImportRowUsesTypedErrorDetail = Expect<
  Equal<RawImportRow['error_detail'], RawImportRowErrorDetail | null | undefined>
>;

/** 三个写入方的真实形状都应当是合法的 error_detail。 */
const parserError: RawImportRowErrorDetail = { message: '数量必须大于 0' };
const lineExceptions: RawImportRowErrorDetail = {
  order_line_exceptions: ['SKU_MAPPING_REQUIRED', 'SKU_MAPPING_CONFLICT'],
};
const reviewSync: RawImportRowErrorDetail = { review_case_reason: 'CUSTOMER_MATCH_REQUIRED' };

void parserError;
void lineExceptions;
void reviewSync;
