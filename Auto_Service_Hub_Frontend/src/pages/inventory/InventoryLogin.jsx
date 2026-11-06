import React, { useState } from "react";
import { Link } from "react-router-dom";
import { useApiLogin } from "../../hooks/useApiLogin";

import "./InventoryLogin.css";

function InventoryLogin() {

  const [showPassword, setShowPassword] = useState(false);

  const [rememberMe, setRememberMe] = useState(true);

  const { submitForm, error, loading } = useApiLogin("/inventory-dashboard");


  return (

    <div className="inventory-login-page">


      {/* =====================================================
          LEFT SIDE
      ===================================================== */}

      <section className="inventory-login-left">

        {/* IMAGE OVERLAY */}

        <div className="inventory-image-overlay"></div>


        {/* BACK TO SITE */}

        <Link
          to="/"
          className="inventory-back"
        >
          ← Back to Site
        </Link>


        {/* LEFT CONTENT */}

        <div className="inventory-left-content">

          <div className="inventory-ai-label">
            — AI-POWERED PLATFORM
          </div>


          <h1>
            AUTO
            <br />

            SERVICE
            <br />

            <span>HUB</span>
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

      <section className="inventory-login-right">

        <div className="inventory-login-container">


          {/* =================================================
              BRAND
          ================================================= */}

          <div className="inventory-login-brand">

            <div className="inventory-brand-icon">
              📦
            </div>


            <div className="inventory-brand-text">

              <h3>
                Auto_Service_Hub
              </h3>

              <p>
                Select your role to continue
              </p>

            </div>

          </div>



          {/* =================================================
              CHANGE ROLE
          ================================================= */}

          <Link
            to="/modules"
            className="inventory-change-role"
          >
            ← Change Role
          </Link>



          {/* =================================================
              ROLE CARD
          ================================================= */}

          <div className="inventory-role-card">

            <div className="inventory-role-icon">
              📦
            </div>


            <div className="inventory-role-details">

              <h3>
                Inventory Manager
              </h3>

              <p>
                Inventory & Procurement
              </p>

            </div>

          </div>



          {/* =================================================
              SIGN IN HEADING
          ================================================= */}

          <div className="inventory-signin-heading">

            <h1>
              SIGN IN
            </h1>

            <p>
              Enter your credentials to access the
              Inventory Manager workspace.
            </p>

          </div>



          {/* =================================================
              LOGIN FORM
          ================================================= */}

          <form
            className="inventory-login-form"
            onSubmit={submitForm}
          >
            {error && <p role="alert" className="login-error">{error}</p>}


            {/* =================================================
                EMAIL
            ================================================= */}

            <label htmlFor="inventory-email">
              EMAIL ADDRESS OR USERNAME
            </label>


            <input
              id="inventory-email"
              type="text"
              name="username"
              placeholder="Email or username"
              autoComplete="username"
              required
            />



            {/* =================================================
                PASSWORD
            ================================================= */}

            <label htmlFor="inventory-password">
              PASSWORD
            </label>


            <div className="inventory-password-wrapper">

              <input
                id="inventory-password"
                name="password"
                type={showPassword ? "text" : "password"}
                placeholder="Enter your password"
                autoComplete="current-password"
                required
              />


              <button
                type="button"
                className="inventory-show-password"
                onClick={() =>
                  setShowPassword(!showPassword)
                }
              >
                {showPassword ? "Hide" : "Show"}
              </button>

            </div>



            {/* =================================================
                OPTIONS
            ================================================= */}

            <div className="inventory-form-options">

              <label className="inventory-remember">

                <input
                  type="checkbox"
                  name="rememberMe"
                  checked={rememberMe}
                  onChange={(e) =>
                    setRememberMe(e.target.checked)
                  }
                />

                <span>
                  Remember me
                </span>

              </label>


              <a
                href="#forgot"
                className="inventory-forgot"
                onClick={(e) =>
                  e.preventDefault()
                }
              >
                Forgot password?
              </a>

            </div>



            {/* =================================================
                ACCESS PLATFORM
            ================================================= */}

            <button
              type="submit"
              className="inventory-login-button"
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


export default InventoryLogin;