import { HttpDesktopService } from "./httpDesktopService.js";
import { MockDesktopService } from "./mockDesktopService.js";
import type { DesktopService } from "./types.js";

export function createDesktopService(locationSearch = window.location.search): DesktopService {
  const params = new URLSearchParams(locationSearch);
  const requestedMockCount = Number(params.get("mock") || import.meta.env.VITE_CYCLONE_MOCK_DEVICES || "0");
  const explicitMock = import.meta.env.VITE_CYCLONE_BACKEND === "mock" || requestedMockCount > 0;
  if (explicitMock) return new MockDesktopService(requestedMockCount > 0 ? requestedMockCount : 4);
  return new HttpDesktopService({
    httpBaseUrl: import.meta.env.VITE_CYCLONE_HTTP_BASE,
    wsBaseUrl: import.meta.env.VITE_CYCLONE_WS_BASE,
  });
}
