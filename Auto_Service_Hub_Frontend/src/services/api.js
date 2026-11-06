const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || "/api/v1").replace(/\/$/, "");

export class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

async function request(path, { method = "GET", body, params, headers = {} } = {}) {
  const url = new URL(`${API_BASE_URL}${path}`, window.location.origin);
  if (params) {
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== "") {
        url.searchParams.set(key, String(value));
      }
    });
  }

  const token =
    sessionStorage.getItem("accessToken") || localStorage.getItem("accessToken");
  const requestHeaders = new Headers(headers);
  requestHeaders.set("Accept", "application/json");
  if (token) requestHeaders.set("Authorization", `Bearer ${token}`);
  if (body !== undefined && !(body instanceof FormData)) {
    requestHeaders.set("Content-Type", "application/json");
  }

  const response = await fetch(url, {
    method,
    headers: requestHeaders,
    body: body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body),
  });

  if (response.status === 401 && path !== "/auth/login") {
    localStorage.removeItem("accessToken");
    localStorage.removeItem("refreshToken");
    localStorage.removeItem("user");
    sessionStorage.removeItem("accessToken");
    sessionStorage.removeItem("refreshToken");
    sessionStorage.removeItem("user");
    window.dispatchEvent(new Event("autoservicehub:unauthorized"));
  }

  if (response.status === 204) return null;

  const contentType = response.headers.get("content-type") || "";
  const responseBody = contentType.includes("application/json")
    ? await response.json()
    : await response.text();

  if (!response.ok) {
    const message =
      typeof responseBody === "object" && responseBody !== null
        ? responseBody.message || responseBody.error || `Request failed (${response.status})`
        : responseBody || `Request failed (${response.status})`;
    throw new ApiError(message, response.status);
  }

  if (typeof responseBody === "object" && responseBody !== null && "success" in responseBody) {
    if (!responseBody.success) {
      throw new ApiError(responseBody.message || "The server rejected the request.", response.status);
    }
    return responseBody.data;
  }

  return responseBody;
}

export const api = {
  get: (path, params) => request(path, { params }),
  post: (path, body) => request(path, { method: "POST", body }),
  put: (path, body) => request(path, { method: "PUT", body }),
  patch: (path, body) => request(path, { method: "PATCH", body }),
  delete: (path) => request(path, { method: "DELETE" }),
  upload: (path, formData) => request(path, { method: "POST", body: formData }),
};

export async function getAllPages(path, size = 100) {
  const results = [];
  let page = 0;
  let totalPages = 1;

  do {
    const result = await api.get(path, { page, size });
    if (
      !result ||
      !Array.isArray(result.content) ||
      !Number.isInteger(result.totalPages) ||
      result.totalPages < 0
    ) {
      throw new ApiError(`Unexpected paginated response from ${path}.`);
    }
    results.push(...result.content);
    totalPages = result.totalPages;
    page += 1;
  } while (page < totalPages);

  return results;
}

export function getErrorMessage(error, fallback = "The request could not be completed.") {
  if (error instanceof Error && error.message) return error.message;
  return fallback;
}

// The merge kept both service layers. The .jsx services (aiService,
// appointmentService, billingService, customerService, dashboardService,
// inventoryService, jobCardService, mechanicService, packageService,
// reportService, userService, vehicleService, authService) were written
// against api.jsx and import from "./api" - which Vite resolves to this
// module. Re-export the api.jsx request helpers so those modules keep
// working without touching every call site.
export { fetchWithAuth, post } from "./api.jsx";
