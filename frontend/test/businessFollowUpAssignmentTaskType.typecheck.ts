/**
 * 客户跟进 Assignment 的 task_type 类型层门禁（票 02）。
 *
 * 关联既有客户与创建新客户两条路径都必须能在不绕过类型系统的前提下表达出来；
 * 任一取值从联合里掉出去，`npm run typecheck` 就会失败。
 */

import type { BusinessFollowUpAssignment } from '../src/api/types';

type Equal<Left, Right> =
  (<Value>() => Value extends Left ? 1 : 2) extends
  (<Value>() => Value extends Right ? 1 : 2)
    ? true
    : false;
type Expect<Value extends true> = Value;

export type AssignmentTaskTypeCoversBothCustomerPaths = Expect<
  Equal<BusinessFollowUpAssignment['task_type'], 'KEHUZX_CUSTOMER_LINK' | 'KEHUZX_CUSTOMER_CREATE'>
>;

const linkExistingCustomer: BusinessFollowUpAssignment['task_type'] = 'KEHUZX_CUSTOMER_LINK';
const createNewCustomer: BusinessFollowUpAssignment['task_type'] = 'KEHUZX_CUSTOMER_CREATE';

void linkExistingCustomer;
void createNewCustomer;
