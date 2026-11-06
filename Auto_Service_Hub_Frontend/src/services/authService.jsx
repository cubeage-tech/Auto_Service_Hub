import { post } from "./api";

const ACCESS_TOKEN_KEY = "accessToken";
const REFRESH_TOKEN_KEY = "refreshToken";
const USER_KEY = "user";

function getUserFromAccessToken(accessToken, fallbackUsername) {
  try {
    const payloadPart = accessToken.split(".")[1];
    if (!payloadPart) {
      return { username: fallbackUsername, role: null };
    }

    const base64 = payloadPart.replace(/-/g, "+").replace(/_/g, "/");
    const payload = JSON.parse(
      atob(base64.padEnd(Math.ceil(base64.length / 4) * 4, "="))
    );
    const username =
      typeof payload.sub === "string" && payload.sub
        ? payload.sub
        : fallbackUsername;
    const rawRole =
      typeof payload.role === "string"
        ? payload.role.replace(/^ROLE_/, "").toUpperCase()
        : null;
    // The backend mints the role claim from the stored role name, and the garage
    // owner's row is stored as GARAGE_OWNER, so the token can carry that raw
    // name. It grants the same permissions as OWNER, so it is collapsed to the
    // canonical name here. Every other role is passed through unchanged.
    const role = rawRole === "GARAGE_OWNER" ? "OWNER" : rawRole;
    return { username, role };
  } catch {
    return { username: fallbackUsername, role: null };
  }
}

export async function login(username, password, rememberMe = false) {
  const response = await post("/auth/login", { username, password });
  if (!response?.success || !response.data) {
    throw new Error(response?.message || "Login was unsuccessful.");
  }

  const { accessToken, refreshToken, tokenType } = response.data;
  if (
    typeof accessToken !== "string" ||
    !accessToken ||
    typeof refreshToken !== "string" ||
    !refreshToken ||
    typeof tokenType !== "string" ||
    tokenType.toLowerCase() !== "bearer"
  ) {
    throw new Error("The authentication API returned an incomplete token response.");
  }

  const persistentStorage = rememberMe ? localStorage : sessionStorage;
  const temporaryStorage = rememberMe ? sessionStorage : localStorage;
  const storageName = rememberMe ? "localStorage" : "sessionStorage";

  for (const storage of [persistentStorage, temporaryStorage]) {
    storage.removeItem(ACCESS_TOKEN_KEY);
    storage.removeItem(REFRESH_TOKEN_KEY);
    storage.removeItem(USER_KEY);
    storage.removeItem("token");
  }

  let accessTokenSaved = false;
  let refreshTokenSaved = false;
  try {
    persistentStorage.setItem(ACCESS_TOKEN_KEY, accessToken);
    accessTokenSaved = persistentStorage.getItem(ACCESS_TOKEN_KEY) === accessToken;
  } catch {
    accessTokenSaved = false;
  }
  try {
    persistentStorage.setItem(REFRESH_TOKEN_KEY, refreshToken);
    refreshTokenSaved = persistentStorage.getItem(REFRESH_TOKEN_KEY) === refreshToken;
  } catch {
    refreshTokenSaved = false;
  }

  if (import.meta.env.DEV) {
    console.info("[auth] token storage result", {
      storage: storageName,
      rememberMe,
      accessTokenPresentInResponse: true,
      refreshTokenPresentInResponse: true,
      accessTokenSaved,
      refreshTokenSaved,
    });
  }
  if (!accessTokenSaved || !refreshTokenSaved) {
    throw new Error("Unable to save the authentication session in this browser.");
  }

  const user = getUserFromAccessToken(accessToken, username);
  persistentStorage.setItem(USER_KEY, JSON.stringify(user));

  return user;
}

export function logout() {
  for (const storage of [localStorage, sessionStorage]) {
    storage.removeItem(ACCESS_TOKEN_KEY);
    storage.removeItem(REFRESH_TOKEN_KEY);
    storage.removeItem(USER_KEY);
    storage.removeItem("token");
  }
}

export function getUser() {
  const hasSession =
    sessionStorage.getItem(ACCESS_TOKEN_KEY) ||
    localStorage.getItem(ACCESS_TOKEN_KEY);
  if (!hasSession) {
    return null;
  }

  const storedUser =
    sessionStorage.getItem(USER_KEY) || localStorage.getItem(USER_KEY);
  if (!storedUser) {
    return null;
  }

  try {
    const user = JSON.parse(storedUser);
    return (
      typeof user.username === "string" &&
      (user.role === null || typeof user.role === "string")
    )
      ? user
      : null;
  } catch {
    return null;
  }
}