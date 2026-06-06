export const API_BASE =
  process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080/api";

export type DropInfo = {
  id: number;
  name: string;
  price: string;
  totalStock: number;
  availableStock: number;
  dropStartsAt: string;
  status: "ACTIVE" | "SOLD_OUT" | "DRAFT" | "ARCHIVED";
};

export type PaymentProviderName = "STRIPE" | "ECPAY" | "PAYUNI";

export type PurchaseRequest = {
  quantity: number;
  buyerName: string;
  buyerEmail: string;
  buyerPhone: string;
  shippingAddress: string;
  provider: PaymentProviderName;
};

export type PurchaseResponse = {
  // UUID externalId — NOT the internal Long DB id. The backend's wire
  // contract is externalId-keyed; treating this as a number broke the
  // result page (Long.parseLong on a UUID).
  orderId: string;
  redirectUrl: string | null;
  formHtml: string | null;
};

export type OrderInfo = {
  id: string;
  productId: number;
  quantity: number;
  buyerName: string;
  buyerEmail: string;
  buyerPhone: string;
  shippingAddress: string;
  amount: string;
  status:
    | "CREATED"
    | "PAID"
    | "SHIPPED"
    | "COMPLETED"
    | "EXPIRED"
    | "CANCELLED";
  provider: string;
  providerRef: string | null;
  createdAt: string;
  expiresAt: string;
};

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

async function parseError(res: Response): Promise<ApiError> {
  let code = "ERROR";
  let detail = res.statusText;
  try {
    const body = await res.json();
    code = body.error ?? code;
    detail = body.detail ?? body.message ?? detail;
  } catch {
    /* non-JSON body */
  }
  return new ApiError(res.status, code, detail);
}

export async function fetchDrops(): Promise<DropInfo[]> {
  const res = await fetch(`${API_BASE}/drops`, { cache: "no-store" });
  if (!res.ok) throw await parseError(res);
  return res.json();
}

export async function fetchDrop(productId: string | number): Promise<DropInfo> {
  const res = await fetch(`${API_BASE}/drops/${productId}`, { cache: "no-store" });
  if (!res.ok) throw await parseError(res);
  return res.json();
}

export type SeedProductRequest = {
  name: string;
  price: number;
  totalStock: number;
  dropStartsAt: string;
};

export async function seedProduct(body: SeedProductRequest): Promise<DropInfo> {
  const res = await fetch(`${API_BASE}/internal/products`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw await parseError(res);
  return res.json();
}

export type DeleteProductResult = { id: number; mode: "deleted" | "archived" };

export async function deleteProduct(id: number): Promise<DeleteProductResult> {
  const res = await fetch(`${API_BASE}/internal/products/${id}`, {
    method: "DELETE",
  });
  if (!res.ok) throw await parseError(res);
  return res.json();
}

export async function purchase(
  productId: string | number,
  body: PurchaseRequest,
): Promise<PurchaseResponse> {
  const res = await fetch(`${API_BASE}/drops/${productId}/purchase`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw await parseError(res);
  return res.json();
}

export async function fetchOrder(orderId: string | number): Promise<OrderInfo> {
  const res = await fetch(`${API_BASE}/orders/${orderId}`, { cache: "no-store" });
  if (!res.ok) throw await parseError(res);
  return res.json();
}
