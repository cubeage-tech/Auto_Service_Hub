import { useContext, useState } from "react";
import { useNavigate } from "react-router-dom";
import { AuthContext } from "../context/AuthContext";
import { getErrorMessage } from "../services/api";

export function useApiLogin(redirectPath, roleRedirectOverrides = {}, expectedRole) {
  const { login, logout } = useContext(AuthContext);
  const navigate = useNavigate();
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  const submit = async (event, username, password, rememberMe) => {
    event.preventDefault();
    setError("");
    setLoading(true);
    try {
      const user = await login(username.trim(), password, rememberMe);
      if (expectedRole && user.role !== expectedRole) {
        logout();
        setError(
          `This account is assigned to ${user.role || "another role"}, not ${expectedRole}. Sign in with a ${expectedRole} account.`
        );
        return;
      }
      const roleRedirects = {
        ADMIN: "/admin",
        OWNER: "/garage-owner/dashboard",
        MANAGER: "/garage-owner/dashboard",
        SERVICE_ADVISOR: "/service-advisor",
        MECHANIC: "/mechanic-dashboard",
        INVENTORY_MANAGER: "/inventory-dashboard",
        BILLING_USER: "/billing/dashboard",
        ...roleRedirectOverrides,
      };
      navigate(roleRedirects[user.role] || redirectPath);
    } catch (requestError) {
      setError(getErrorMessage(requestError, "Sign in failed."));
    } finally {
      setLoading(false);
    }
  };

  const submitForm = (event) => {
    const formData = new FormData(event.currentTarget);
    const username = formData.get("username") || formData.get("email");
    const password = formData.get("password");
    const rememberMe = formData.get("rememberMe") === "on";
    void submit(event, String(username || ""), String(password || ""), rememberMe);
  };

  return { submit, submitForm, error, loading };
}
