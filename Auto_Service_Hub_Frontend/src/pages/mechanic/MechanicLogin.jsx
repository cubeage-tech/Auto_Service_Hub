import React, { useState } from "react";
import { Link } from "react-router-dom";
import { useApiLogin } from "../../hooks/useApiLogin";

import "./MechanicLogin.css";

function MechanicLogin() {
  const [showPassword, setShowPassword] = useState(false);
  const [rememberMe, setRememberMe] = useState(false);
  const { submitForm, error, loading } = useApiLogin(
    "/mechanic-dashboard",
    {},
    "MECHANIC"
  );


  return (

    <div className="mechanic-login-page">


      {/* =====================================================
          LEFT SIDE
      ===================================================== */}

      <section className="mechanic-login-left">


        {/* DARK IMAGE OVERLAY */}

        <div className="mechanic-image-overlay"></div>


        {/* BACK TO SITE */}

        <Link
          to="/"
          className="mechanic-back"
        >
          ← Back to Site
        </Link>


        {/* LEFT CONTENT */}

        <div className="mechanic-left-content">


          <div className="mechanic-ai-label">
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

      <section className="mechanic-login-right">


        <div className="mechanic-login-container">


          {/* =================================================
              BRAND
          ================================================= */}

          <div className="mechanic-login-brand">


            <div className="mechanic-brand-icon">
              ▰
            </div>


            <div className="mechanic-brand-text">

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
            className="mechanic-change-role"
          >
            ← Change Role
          </Link>



          {/* =================================================
              ROLE CARD
          ================================================= */}

          <div className="mechanic-role-card">


            <div className="mechanic-role-icon">
              🔧
            </div>


            <div className="mechanic-role-details">

              <h3>
                Mechanic
              </h3>

              <p>
                Technician
              </p>

            </div>


          </div>



          {/* =================================================
              SIGN IN
          ================================================= */}

          <div className="mechanic-signin-heading">

            <h1>
              SIGN IN
            </h1>

            <p>
              Enter your credentials to access the
              Mechanic workspace.
            </p>

          </div>



          {/* =================================================
              LOGIN FORM
          ================================================= */}

          <form
            className="mechanic-login-form"
            onSubmit={submitForm}
          >
            {error && <p role="alert" className="login-error">{error}</p>}


            {/* =================================================
                EMAIL
            ================================================= */}

            <label htmlFor="mechanic-email">
              EMAIL ADDRESS OR USERNAME
            </label>


            <input
              id="mechanic-email"
              type="text"
              name="username"
              placeholder="Email or username"
              autoComplete="username"
              required
            />



            {/* =================================================
                PASSWORD
            ================================================= */}

            <label htmlFor="mechanic-password">
              PASSWORD
            </label>


            <div className="mechanic-password-wrapper">


              <input
                id="mechanic-password"
                name="password"
                type={showPassword ? "text" : "password"}
                placeholder="••••••••••••"
                autoComplete="current-password"
                required
              />


              <button
                type="button"
                className="mechanic-show-password"
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

            <div className="mechanic-form-options">


              <label className="mechanic-remember">


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
                className="mechanic-forgot"
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
              className="mechanic-login-button"
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


export default MechanicLogin;