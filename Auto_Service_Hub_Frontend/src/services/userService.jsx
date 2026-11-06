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
    throw new Error(`Unable to reach the user API at ${url}.${detail}`);
  }

  let body;
  try {
    body = response.status === 204 ? null : await response.json();
  } catch {
    if (!response.ok) {
      throw new Error(`User API request failed (${response.status}).`);
    }
    throw new Error(`User API returned invalid JSON (${response.status}).`);
  }

  if (!response.ok) {
    /* 400 carries the bean-validation text, 409 the business rule
       that refused the account (an unknown role, a username or
       email that is already taken), 403 the role guard. The message
       is the backend's own, so the caller shows it as it is. */
    const message = body?.message || `User API request failed (${response.status}).`;
    if (response.status === 401 || response.status === 403) {
      throw new Error(`${message} Sign in with an authorized account and try again.`);
    }
    throw new Error(message);
  }

  if (!body?.success || body.data == null) {
    throw new Error(body?.message || "User API returned an unexpected response.");
  }

  return body.data;
}

/**
 * Create a user account: POST /api/v1/users
 *
 * The body is UserRequestDTO: fullName, username, email, phone, role,
 * password and the optional status. `confirmPassword` is a form
 * convenience and is never sent.
 *
 * The response body is UserResponseDTO: id, fullName, username, email,
 * phone, role, active and the timestamps - there is no credential field
 * in it at all. `role` comes back as the name stored in the roles table
 * (e.g. SERVICE_ADVISOR), not as the display label the form showed.
 *
 * Authorization is enforced server-side (OWNER). A caller without
 * that role is answered 403 by the project's handler, and a
 * 400 carries the bean-validation text while a 409 carries the business
 * rule that refused the account - an unknown role, or a username or
 * email that is already taken. Both messages are the backend's own and
 * are surfaced here unchanged for the caller to display.
 */
export function createUser(account) {
  return requestData("/users", {
    method: "POST",
    body: JSON.stringify(account),
  });
}

/**
 * The backend answers a list as a Spring Page, so the records are read
 * page by page and flattened in the order the API returned them. The
 * loop stops on the API's own `totalPages`, so it never guesses how many
 * pages there are and never leaves a page behind.
 */
async function getAllPages(path, sort) {
  const pageSize = 100;
  const rows = [];

  for (let pageNumber = 0; ; pageNumber += 1) {
    const separator = path.includes("?") ? "&" : "?";
    const page = await requestData(
      `${path}${separator}page=${pageNumber}&size=${pageSize}&sort=${sort}`
    );

    if (!Array.isArray(page?.content)) {
      throw new Error("User API returned an unexpected page format.");
    }

    rows.push(...page.content);
    if (pageNumber + 1 >= page.totalPages || page.content.length === 0) {
      return rows;
    }
  }
}

/**
 * Every user account: GET /api/v1/users?page=0&size=100&sort=id,desc
 *
 * The endpoint is a paged read (Page<UserResponseDTO> inside the standard
 * envelope), and it returns the same projection the create call does: id,
 * fullName, username, email, phone, role, active, createdAt and updatedAt.
 * There is no credential field on it. `role` is the name the roles table
 * stores (e.g. GARAGE_OWNER, SERVICE_ADVISOR), not a display label, and
 * `active` is the boolean the screen shows as Active / Inactive.
 *
 * `id,desc` puts the most recently created account first, so the page can
 * show what was just created without hunting for it.
 *
 * Authorization is enforced server-side (OWNER), like the create call: a
 * caller without that role is answered 403, and the message explains it.
 */
export function getUsers() {
  return getAllPages("/users", "id,desc");
}
