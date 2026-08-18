import type { SkuAttributes, SkuPage, SkuRecord } from '../src/api/types';

type Equal<Left, Right> =
  (<Value>() => Value extends Left ? 1 : 2) extends
  (<Value>() => Value extends Right ? 1 : 2)
    ? true
    : false;
type Expect<Value extends true> = Value;

export type PurchasePriceIsRequiredNullable = Expect<
  Equal<SkuAttributes['purchase_price'], string | null>
>;
export type RetailPriceIsRequiredNullable = Expect<
  Equal<SkuAttributes['retail_price'], string | null>
>;
export type SkuRecordHasTypedAttributes = Expect<
  Equal<SkuRecord['attributes'], SkuAttributes>
>;
export type SkuPageContainsTypedRecords = Expect<
  Equal<SkuPage['items'][number], SkuRecord>
>;
