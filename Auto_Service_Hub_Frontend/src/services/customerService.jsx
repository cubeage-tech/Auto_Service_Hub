import { fetchWithAuth } from "./api";

const API_BASE_URL = (
  import.meta.env.VITE_API_BASE_URL ||
  (import.meta.env.DEV ? "/api/v1" : "http://localhost:8081/api/v1")
).replace(/\/+$/, "");

async function requestData(path, options = {}) {
  const url = `${API_BASE_URL}${path}`;
  let response;

  try {
    response = await fetchWithAuth(url, {
      ...options,
      headers: {
        ...(options.body ? { "Content-Type": "application/json" } : {}),
        ...options.headers,
      },
    });
  } catch (error) {
    const detail = error instanceof Error ? ` (${error.message})` : "";
    throw new Error(`Unable to reach the customer API at ${url}.${detail}`);
  }

  let body;
  try {
    body = response.status === 204 ? null : await response.json();
  } catch {
    if (!response.ok) {
      throw new Error(`Customer API request failed (${response.status}).`);
    }
    throw new Error(`Customer API returned invalid JSON (${response.status}).`);
  }

  if (!response.ok) {
    const message = body?.message || `Customer API request failed (${response.status}).`;
    if (response.status === 401 || response.status === 403) {
      throw new Error(`${message} Sign in with an authorized account and try again.`);
    }
    throw new Error(message);
  }

  if (!body?.success || body.data == null) {
    throw new Error(body?.message || "Customer API returned an unexpected response.");
  }

  return body.data;
}

export async function getCustomers() {
  const pageSize = 100;
  const customers = [];

  for (let pageNumber = 0; ; pageNumber += 1) {
    const page = await requestData(
      `/customers?page=${pageNumber}&size=${pageSize}&sort=id,desc`
    );

    if (!Array.isArray(page?.content)) {
      throw new Error("Customer list API returned an unexpected page format.");
    }

    customers.push(...page.content);
    if (pageNumber + 1 >= page.totalPages || page.content.length === 0) {
      return customers;
    }
  }
}

export function createCustomer(customer) {
  return requestData("/customers", {
    method: "POST",
    body: JSON.stringify(customer),
  });
}
