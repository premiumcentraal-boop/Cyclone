import type { GatewayClient } from "./gateway.js";

export type ProviderKind = "vmos" | "smsbot";
export interface ProviderConnection {
  id: ProviderKind; title: string; connected: boolean; status: string; agentEnabled: boolean;
  apps: string[]; routines: string[]; currency: string; plugin: string;
}
export interface RentalQuote {
  country: string; days: number; services: string[]; amountCents: number; currency: string;
  autoRenew: boolean; quantity: number; terms: string; accountId: string | null;
}
export interface NumberOrder {
  id: string; provider: ProviderKind; requestId: string; status: string; quote: RentalQuote;
  approvalId: string | null; number: string | null; rentalId: string | null;
  numberId: string | null; expiresAt: number; error: string | null;
}
export interface ProviderOverview {
  providers: ProviderConnection[]; orders: NumberOrder[]; keyStorage: string; keyStoreError: boolean;
}
export interface Catalogue {
  countries: Array<{ code: string; name: string; available: boolean; plans: Array<{ id: number; days: number; amountCents: number; currency: string }> }>;
  templates: Array<{ id: string; name: string; country: string; periods: string[] }>;
}
export const numberProvidersApi = {
  overview: (c: GatewayClient) => c.get<ProviderOverview>("/v1/number-providers"),
  configure: (c: GatewayClient, kind: ProviderKind, config: Record<string, unknown>) => c.post<ProviderOverview>(`/v1/number-providers/${kind}/config`, config),
  catalogue: (c: GatewayClient, kind: ProviderKind, areaCode: string) => c.get<Catalogue>(`/v1/number-providers/${kind}/catalogue?areaCode=${encodeURIComponent(areaCode)}`),
  quote: (c: GatewayClient, kind: ProviderKind, selection: Record<string, unknown>) => c.post<NumberOrder>(`/v1/number-providers/${kind}/quotes`, selection),
  requestApproval: (c: GatewayClient, id: string) => c.post<NumberOrder>(`/v1/number-orders/${encodeURIComponent(id)}/request-approval`),
  reconcile: (c: GatewayClient, id: string, rentalId: string) => c.post<NumberOrder>(`/v1/number-orders/${encodeURIComponent(id)}/reconcile`, { rentalId }),
};
export const rentalPrice = (q: Pick<RentalQuote, "amountCents" | "currency">): string => `${(q.amountCents / 100).toFixed(2)} ${q.currency}`;
