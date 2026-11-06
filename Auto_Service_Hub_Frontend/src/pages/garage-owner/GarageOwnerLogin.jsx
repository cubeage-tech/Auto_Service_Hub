import React, { useState } from "react";
import { Link } from "react-router-dom";
import { useApiLogin } from "../../hooks/useApiLogin";
import "./GarageOwnerLogin.css";

function GarageOwnerLogin() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [rememberMe, setRememberMe] = useState(false);
  const { submit, error, loading } = useApiLogin("/garage-owner/dashboard");

  return (
    <div className="garage-login-page">

      {/* =====================================================
          LEFT SIDE
      ===================================================== */}

      <section className="garage-login-left">

        <Link
          to="/"
          className="garage-login-back"
        >
          ← Back to Site
        </Link>

        <div className="garage-login-overlay"></div>

        <div className="garage-login-left-content">

          <div className="garage-login-platform-label">
            — AI-POWERED PLATFORM
          </div>

          <h1>
            SMART.
            <br />
            GARAGE.
            <br />
            <span>AI.</span>
          </h1>

          <p>
            Manage every aspect of your workshop —
            customers, jobs, inventory, billing and AI insights.
          </p>

        </div>

      </section>


      {/* =====================================================
          RIGHT SIDE
      ===================================================== */}

      <section className="garage-login-right">

        <div className="garage-login-container">

          {/* =================================================
              BRAND
          ================================================= */}

          <div className="garage-login-brand">

            <div className="garage-login-brand-icon">
              ▰
            </div>

            <div className="garage-login-brand-text">

              <div className="garage-login-brand-title">
                Auto_Service_Hub AI CRM
              </div>

              <div className="garage-login-brand-subtitle">
                Select your role to continue
              </div>

            </div>

          </div>


          {/* =================================================
              CHANGE ROLE
          ================================================= */}

          <Link
            to="/modules"
            className="garage-login-change-role"
          >
            ← Change Role
          </Link>


          {/* =================================================
              GARAGE OWNER ROLE
          ================================================= */}

          <div className="garage-login-role-card">

            <div className="garage-login-role-icon">
              ⚙
            </div>

            <div className="garage-login-role-content">

              <h3>
                Garage Owner
              </h3>

              <span>
                Business Admin
              </span>

            </div>

          </div>


          {/* =================================================
              SIGN IN HEADING
          ================================================= */}

          <div className="garage-login-heading">

            <h2>
              SIGN IN
            </h2>

            <p>
              Enter your credentials to access the Garage Owner
              workspace.
            </p>

          </div>


          {/* =================================================
              LOGIN FORM
          ================================================= */}

          <form
            className="garage-login-form"
            onSubmit={(event) => submit(event, email, password, rememberMe)}
          >
            {error && <p role="alert" className="login-error">{error}</p>}

            {/* EMAIL */}

            <div className="garage-login-field">

              <label htmlFor="garage-email">
                EMAIL ADDRESS OR USERNAME
              </label>

              <input
                id="garage-email"
                type="text"
                name="username"
                placeholder="Email or username"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                autoComplete="username"
                required
              />

            </div>


            {/* PASSWORD */}

            <div className="garage-login-field">

              <label htmlFor="garage-password">
                PASSWORD
              </label>

              <input
                id="garage-password"
                type="password"
                name="password"
                placeholder="••••••••••"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
                autoComplete="current-password"
                autoComplete="current-password"
                required
              />

            </div>


            {/* REMEMBER / FORGOT */}

            <div className="garage-login-options">

              <label className="garage-remember">

                <input
                  type="checkbox"
                  checked={rememberMe}
                  onChange={(e) =>
                    setRememberMe(e.target.checked)
                  }
                />

                <span>
                  Remember me
                </span>

              </label>

              <button
                type="button"
                className="garage-forgot-password"
                onClick={() =>
                  alert("Password recovery will be added later.")
                }
              >
                Forgot password?
              </button>

            </div>


            {/* ACCESS PLATFORM */}

            <button
              type="submit"
              className="garage-access-button"
              disabled={loading}
            >
              {loading ? "SIGNING IN..." : "ACCESS PLATFORM →"}
            </button>

          </form>

        </div>

      </section>

    </div>
  );
}

export default GarageOwnerLogin;