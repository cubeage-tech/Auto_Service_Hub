import React, { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useAuth } from "../../hooks/useAuth";
import "./CustomerLogin.css";

function CustomerLogin() {
  const navigate = useNavigate();
  const { login } = useAuth();
  const [rememberMe, setRememberMe] = useState(true);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  const handleLogin = async (e) => {
    e.preventDefault();
    setError("");
    setLoading(true);
    const formData = new FormData(e.currentTarget);
    try {
      await login(
        formData.get("username"),
        formData.get("password"),
        rememberMe
      );
      navigate("/customer-dashboard");
    } catch (loginError) {
      setError(loginError.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="login-container">
      {/* LEFT SIDE - BRANDING */}
      <div className="login-left">
        <Link to="/" className="back-link">
          ← Back to Site
        </Link>
        <div className="left-overlay"></div>
        <div className="left-content">
          <span className="brand-tag">— AI-POWERED PLATFORM</span>
          <h1 className="brand-title">
            SMART.<br />
            GARAGE.<br />
            <span className="highlight">AI.</span>
          </h1>
          <p className="brand-desc">
            Manage every aspect of your workshop — customers, jobs, inventory, billing and AI insights.
          </p>
        </div>
      </div>

      {/* RIGHT SIDE - FORM */}
      <div className="login-right">
        <div className="form-wrapper">
          {/* TOP LOGO HEADER */}
          <div className="header-logo">
            <div className="logo-box">▰</div>
            <div>
              <div className="logo-title">Auto_Service_Hub</div>
              <div className="logo-subtitle">Select your role to continue</div>
            </div>
          </div>

          <Link to="/modules" className="change-role-btn">
            ← Change Role
          </Link>

          {/* ACTIVE ROLE CARD */}
          <div className="active-role-card">
            <span className="role-card-icon">🚗</span>
            <div>
              <div className="role-card-title">Customer</div>
              <div className="role-card-subtitle">Self-Service Portal</div>
            </div>
          </div>

          {/* SIGN IN FORM */}
          <div className="form-section">
            <h2 className="form-title">SIGN IN</h2>
            <p className="form-subtitle">Enter your credentials to access the Customer workspace.</p>

            <form onSubmit={handleLogin}>
              {error && <p role="alert">{error}</p>}
              <div className="input-group">
                <label>EMAIL ADDRESS</label>
                <input
                  name="username"
                  type="text"
                  placeholder="you@garage.com"
                  autoComplete="username"
                  required
                />
              </div>

              <div className="input-group">
                <label>PASSWORD</label>
                <input
                  name="password"
                  type="password"
                  autoComplete="current-password"
                  required
                />
              </div>

              <div className="form-actions">
                <label className="remember-me">
                  <input
                    type="checkbox"
                    checked={rememberMe}
                    onChange={(e) => setRememberMe(e.target.checked)}
                  />
                  <span>Remember me</span>
                </label>
                <a href="#forgot" className="forgot-pass">Forgot password?</a>
              </div>

              <button type="submit" className="submit-btn" disabled={loading}>
                {loading ? "SIGNING IN..." : "ACCESS PLATFORM →"}
              </button>
            </form>
          </div>
        </div>
      </div>
    </div>
  );
}

export default CustomerLogin;