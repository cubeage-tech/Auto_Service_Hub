const API_BASE_URL = (
  import.meta.env.VITE_API_BASE_URL ||
  (import.meta.env.DEV ? "/api/v1" : "http://localhost:8081/api/v1")
).replace(/\/+$/, "");

const getAccessToken = () =>
  sessionStorage.getItem("accessToken") ||
  localStorage.getItem("accessToken");

function redactDiagnosticBody(body) {
  return body
    .replace(
      /("(?:access[_-]?token|refresh[_-]?token|token|password|secret|credential|username|email|authorization)"\s*:\s*)"(?:\\.|[^"\\])*"/gi,
      '$1"[REDACTED]"'
    )
    .replace(/\bBearer\s+[A-Za-z0-9._~+/-]+=*/gi, "Bearer [REDACTED]")
    .replace(/\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b/g, "[REDACTED_JWT]")
    .slice(0, 1500);
}

async function logResponseDiagnostic(label, url, response, accessTokenPresent) {
  if (!import.meta.env.DEV) {
    return;
  }

  let body = "";
  if (!response.ok) {
    try {
      body = redactDiagnosticBody(await response.clone().text());
    } catch {
      body = "[response body unavailable]";
    }
  }

  console.info(`[auth] ${label}`, {
    url,
    status: response.status,
    accessTokenPresent,
    ...(body ? { errorBody: body } : {}),
  });
}

async function refreshAccessToken() {
  const storage = sessionStorage.getItem("refreshToken")
    ? sessionStorage
    : localStorage.getItem("refreshToken")
      ? localStorage
      : null;
  const refreshToken = storage?.getItem("refreshToken");

  if (!storage || !refreshToken) {
    if (import.meta.env.DEV) {
      console.info("[auth] POST refresh skipped", {
        url: `${API_BASE_URL}/auth/refresh`,
        refreshTokenPresent: false,
      });
    }
    return null;
  }

  const refreshUrl = `${API_BASE_URL}/auth/refresh`;
  let response;
  try {
    response = await fetch(refreshUrl, {
      method: "POST",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
      },
      body: JSON.stringify(refreshToken),
    });
  } catch {
    if (import.meta.env.DEV) {
      console.warn("[auth] POST refresh failed", {
        url: refreshUrl,
        status: "network-error",
        refreshTokenPresent: true,
        tokenValueLogged: false,
      });
    }
    throw new Error("Token refresh request could not reach the authentication API.");
  }
  await logResponseDiagnostic(
    `POST refresh ${response.ok ? "succeeded" : "failed"}`,
    refreshUrl,
    response,
    false
  );

  let payload;
  try {
    payload = await response.json();
  } catch {
    throw new Error(`Token refresh returned invalid JSON (${response.status}).`);
  }

  if (!response.ok || !payload?.success || !payload.data) {
    throw new Error(
      payload?.message || `Token refresh failed (${response.status}). Sign in again.`
    );
  }

  const { accessToken, refreshToken: nextRefreshToken, tokenType } = payload.data;
  if (
    typeof accessToken !== "string" ||
    !accessToken ||
    typeof nextRefreshToken !== "string" ||
    !nextRefreshToken ||
    typeof tokenType !== "string" ||
    tokenType.toLowerCase() !== "bearer"
  ) {
    throw new Error("Token refresh returned an incomplete token response.");
  }

  storage.setItem("accessToken", accessToken);
  storage.setItem("refreshToken", nextRefreshToken);
  if (import.meta.env.DEV) {
    console.info("[auth] refreshed access token saved", {
      storage: storage === sessionStorage ? "sessionStorage" : "localStorage",
      key: "accessToken",
      tokenValueLogged: false,
    });
  }
  return accessToken;
}

async function sendAuthorizedRequest(url, options, accessToken, attempt) {
  const headers = new Headers(options.headers);
  headers.set("Accept", "application/json");
  if (accessToken) {
    headers.set("Authorization", `Bearer ${accessToken}`);
  } else {
    headers.delete("Authorization");
  }

  if (import.meta.env.DEV) {
    const authorization = headers.get("Authorization") || "";
    console.info("[auth] sending authenticated request", {
      url,
      attempt,
      authorizationHeaderPresent: headers.has("Authorization"),
      bearerTokenPresent: authorization.startsWith("Bearer ") &&
        authorization.length > "Bearer ".length,
      accessTokenMatchesStorage:
        attempt === "retry" && accessToken
          ? getAccessToken() === accessToken
          : undefined,
      tokenValueLogged: false,
    });
  }

  return fetch(url, { ...options, headers });
}

export async function fetchWithAuth(url, options = {}) {
  const accessToken = getAccessToken();
  let response = await sendAuthorizedRequest(url, options, accessToken, "initial");
  await logResponseDiagnostic(
    `${options.method || "GET"} request`,
    url,
    response,
    Boolean(accessToken)
  );
  if (response.status !== 401) {
    return response;
  }

  const renewedAccessToken = await refreshAccessToken();
  if (!renewedAccessToken) {
    return response;
  }

  if (import.meta.env.DEV) {
    console.info("[auth] retrying original request with refreshed access token", {
      url,
      accessTokenPresent: true,
      tokenValueLogged: false,
    });
  }
  response = await sendAuthorizedRequest(url, options, renewedAccessToken, "retry");
  await logResponseDiagnostic(
    `${options.method || "GET"} retry`,
    url,
    response,
    true
  );
  return response;
}

export async function post(path, body) {
  let response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      method: "POST",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
    });
  } catch (error) {
    const detail = error instanceof Error ? ` (${error.message})` : "";
    throw new Error(
      `Unable to reach the authentication API at ${API_BASE_URL}${path}.${detail}`
    );
  }

  let payload;
  try {
    payload = await response.json();
  } catch {
    throw new Error(`Authentication API returned invalid JSON (${response.status}).`);
  }

  if (import.meta.env.DEV && path === "/auth/login") {
    console.info("[auth] login API response", {
      url: `${API_BASE_URL}${path}`,
      status: response.status,
      requestSucceeded: response.ok && Boolean(payload?.success),
      accessTokenPresent: Boolean(payload?.data?.accessToken),
      refreshTokenPresent: Boolean(payload?.data?.refreshToken),
    });
  }

  if (!response.ok) {
    throw new Error(
      payload?.message || `Authentication request failed (${response.status}).`
    );
  }

  return payload;
}