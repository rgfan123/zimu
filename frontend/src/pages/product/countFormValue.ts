const INT32_MAX = 2_147_483_647;

/** Converts Ant form/legacy integral values into the JSON integer count contract. */
export function positiveCountFormValue(value: unknown): number {
  if (value == null || (typeof value === 'string' && value.trim() === '')) {
    throw new Error('数量必须是 int32 正整数');
  }
  if (typeof value !== 'string' && typeof value !== 'number') {
    throw new Error('数量必须是 int32 正整数');
  }
  if (typeof value === 'string' && !/^[1-9][0-9]*$/.test(value.trim())) {
    throw new Error('数量必须是 int32 正整数');
  }
  const normalized = typeof value === 'number' ? value : Number(value.trim());
  if (!Number.isSafeInteger(normalized) || normalized <= 0 || normalized > INT32_MAX) {
    throw new Error('数量必须是 int32 正整数');
  }
  return normalized;
}

/** Keeps legacy/incomplete master-data rows editable so the operator can repair them. */
export function editablePositiveCountFormValue(value: unknown): number | undefined {
  try {
    return positiveCountFormValue(value);
  } catch {
    if (typeof value !== 'string' || value.trim() === '') return undefined;
    const legacy = Number(value.trim());
    return Number.isSafeInteger(legacy) && legacy > 0 && legacy <= INT32_MAX
      ? legacy
      : undefined;
  }
}
