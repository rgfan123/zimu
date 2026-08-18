export interface ProductIdentityInput {
  name?: string | null;
  code?: string | null;
  meta?: Array<string | null | undefined>;
}

export interface ProductIdentityPresentation {
  primary: string;
  secondary?: string;
  meta?: string;
}

function text(value: string | null | undefined): string | undefined {
  const normalized = value?.trim();
  return normalized || undefined;
}

export function productIdentityPresentation(input: ProductIdentityInput): ProductIdentityPresentation {
  const name = text(input.name);
  const code = text(input.code);
  const meta = input.meta?.map(text).filter((value): value is string => Boolean(value)).join(' · ') || undefined;
  return {
    primary: name ?? code ?? '未命名商品',
    secondary: name ? code : undefined,
    meta,
  };
}
