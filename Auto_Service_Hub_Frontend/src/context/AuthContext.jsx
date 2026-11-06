import React, { createContext, useCallback, useEffect, useMemo, useState } from "react";
import { getUser, login as authenticate, logout as clearSession } from "../services/authService";

export const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(getUser);

  const login = useCallback(async (username, password, rememberMe) => {
    const authenticatedUser = await authenticate(username, password, rememberMe);
    setUser(authenticatedUser);
    return authenticatedUser;
  }, []);

  const logout = useCallback(() => {
    clearSession();
    setUser(null);
  }, []);

  useEffect(() => {
    const expireSession = () => setUser(null);
    window.addEventListener("autoservicehub:unauthorized", expireSession);
    return () => window.removeEventListener("autoservicehub:unauthorized", expireSession);
  }, []);

  const value = useMemo(
    () => ({ user, login, logout, isAuthenticated: Boolean(user) }),
    [user, login, logout]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}