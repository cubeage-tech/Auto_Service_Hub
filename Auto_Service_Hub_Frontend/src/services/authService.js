import { api } from "./api";

const ACCESS_TOKEN_KEY = "accessToken";
const REFRESH_TOKEN_KEY = "refreshToken";
const USER_KEY = "user";

function clearAuthStorage() {
  [localStorage, sessionStorage].forEach((storage) => {
    storage.removeItem(ACCESS_TOKEN_KEY);
    storage.removeItem(REFRESH_TOKEN_KEY);
    storage.removeItem(USER_KEY);
  });
}

export async function login(username, password, rememberMe = false) {
  const result = await api.post("/auth/login", { username, password });
  if (!result?.accessToken) {
    throw new Error("The server did not return an access token.");
  }

  // "Remember me" persists the session in localStorage; otherwise the
  // session lives in sessionStorage and is dropped when the tab closes.
  const storage = rememberMe ? localStorage : sessionStorage;

  clearAuthStorage();
  storage.setItem(ACCESS_TOKEN_KEY, result.accessToken);
  if (result.refreshToken) storage.setItem(REFRESH_TOKEN_KEY, result.refreshToken);
  const user = {
    username: result.username || username,
    role: String(result.role || "").replace(/^ROLE_/, "").toUpperCase(),
    fullName: result.fullName || "",
  };
  storage.setItem(USER_KEY, JSON.stringify(user));
  return user;
}

export function logout() {
  clearAuthStorage();
}

export function getUser() {
  const serialized = sessionStorage.getItem(USER_KEY) || localStorage.getItem(USER_KEY);
  if (!serialized) return null;

  try {
    const user = JSON.parse(serialized);
    if (!user?.role) {
      logout();
      return null;
    }
    return user;
  } catch {
    logout();
    return null;
  }
}
