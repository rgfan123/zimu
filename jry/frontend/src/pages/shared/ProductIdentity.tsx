import { productIdentityPresentation, type ProductIdentityInput } from './productIdentityPresentation';
import './productIdentity.css';

export interface ProductIdentityProps extends ProductIdentityInput {
  className?: string;
}

export function ProductIdentity({ name, code, meta, className }: ProductIdentityProps) {
  const presentation = productIdentityPresentation({ name, code, meta });
  return (
    <span className={['product-identity', className].filter(Boolean).join(' ')}>
      <span className="product-identity__name" title={presentation.primary}>
        {presentation.primary}
      </span>
      {presentation.secondary ? (
        <span className="product-identity__code" title={presentation.secondary}>
          {presentation.secondary}
        </span>
      ) : null}
      {presentation.meta ? (
        <span className="product-identity__meta" title={presentation.meta}>
          {presentation.meta}
        </span>
      ) : null}
    </span>
  );
}
