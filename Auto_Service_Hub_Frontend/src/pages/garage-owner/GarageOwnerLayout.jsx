import React, { useState } from "react";
import {
  NavLink,
  Outlet,
  useLocation,
  useNavigate,
} from "react-router-dom";

import { useAuth } from "../../hooks/useAuth";

import "./GarageOwnerLayout.css";

function GarageOwnerLayout() {
  const location = useLocation();
  const navigate = useNavigate();

  const [sidebarOpen, setSidebarOpen] = useState(true);

  // Detect which module is currently open
  const isDeveloper = location.pathname.startsWith("/developer");

  // Dynamic base path
  const basePath = isDeveloper
    ? "/developer"
    : "/garage-owner";

  /*
   * Users and Roles is Owner-only (SRS 13): every other role — MANAGER
   * included — must not reach it, which is the rule UserController already
   * enforces with hasAnyRole('OWNER') on its endpoints. GARAGE_OWNER is
   * accepted beside OWNER for the same reason the billing page accepts it: the
   * token can carry the raw stored role name.
   */
  const { user } = useAuth();
  const role = String(user?.role || "").replace(/^ROLE_/, "").toUpperCase();
  const isOwner = ["OWNER", "GARAGE_OWNER"].includes(role);

  const menuItems = [
    {
      label: "Dashboard",
      path: `${basePath}/dashboard`,
      icon: "▦",
    },
    {
      label: "Customers",
      path: `${basePath}/customers`,
      icon: "♧",
    },
    {
      label: "Vehicles",
      path: `${basePath}/vehicles`,
      icon: "▱",
    },
    {
      label: "Appointments",
      path: `${basePath}/appointments`,
      icon: "□",
    },
    {
      label: "Job Cards",
      path: `${basePath}/jobcards`,
      icon: "▢",
    },
    {
      label: "Mechanics",
      path: `${basePath}/mechanics`,
      icon: "⚒",
    },
    {
      label: "Inventory",
      path: `${basePath}/inventory`,
      icon: "◇",
    },
    {
      label: "Packages",
      path: `${basePath}/packages`,
      icon: "⬡",
    },
    {
      label: "Billing",
      path: `${basePath}/billing`,
      icon: "☆",
    },
    {
      label: "Follow-up",
      path: `${basePath}/follow-up`,
      icon: "□",
    },
    {
      label: "AI Hub",
      path: `${basePath}/ai-hub`,
      icon: "?",
    },
    {
      label: "Reports",
      path: `${basePath}/reports`,
      icon: "▥",
    },
    {
      // Owner-only: dropped from visibleMenuItems for every other role, so the
      // sidebar never offers a screen the route guard would refuse.
      label: "User Management",
      path: `${basePath}/users`,
      icon: "⚷",
      ownerOnly: true,
    },
  ];

  const visibleMenuItems = menuItems.filter(
    (item) => !item.ownerOnly || isOwner
  );

  const getPageName = () => {
    const pathname = location.pathname;

    if (
      pathname === "/garage-owner" ||
      pathname === "/garage-owner/" ||
      pathname === "/garage-owner/dashboard" ||
      pathname === "/developer" ||
      pathname === "/developer/" ||
      pathname === "/developer/dashboard"
    ) {
      return "Dashboard";
    }

    if (pathname.includes("/customers")) {
      return "Customers";
    }

    if (pathname.includes("/vehicles")) {
      return "Vehicles";
    }

    if (pathname.includes("/appointments")) {
      return "Appointments";
    }

    if (pathname.includes("/jobcards")) {
      return "Jobcards";
    }

    if (pathname.includes("/mechanics")) {
      return "Mechanics";
    }

    if (pathname.includes("/inventory")) {
      return "Inventory";
    }

    if (pathname.includes("/packages")) {
      return "Packages";
    }

    if (pathname.includes("/billing")) {
      return "Billing";
    }

    if (pathname.includes("/follow-up")) {
      return "Follow-up";
    }

    if (pathname.includes("/ai-hub")) {
      return "AI Hub";
    }

    if (pathname.includes("/reports")) {
      return "Reports";
    }

    if (pathname.includes("/users")) {
      return "User Management";
    }

    return "Dashboard";
  };

  return (
    <div
      className={`garage-owner-layout ${
        sidebarOpen ? "sidebar-open" : "sidebar-closed"
      }`}
    >

      {/* =====================================================
          SIDEBAR
      ===================================================== */}

      <aside className="garage-owner-sidebar">

        {/* BRAND */}

        <div className="garage-owner-brand">

          <div className="garage-owner-brand-icon">
            <span></span>
          </div>

          <div className="garage-owner-brand-name">
            Auto_Service_Hub
          </div>

        </div>


        {/* NAVIGATION */}

        <nav className="garage-owner-nav">

          {visibleMenuItems.map((item) => (
            <NavLink
              key={item.label}
              to={item.path}
              className={({ isActive }) =>
                `garage-owner-nav-item ${
                  isActive ? "active" : ""
                }`
              }
            >

              <span className="garage-owner-nav-icon">
                {item.icon}
              </span>

              <span className="garage-owner-nav-label">
                {item.label}
              </span>

            </NavLink>
          ))}

        </nav>


        {/* =================================================
            SIDEBAR OPEN / CLOSE
        ================================================= */}

        <button
          type="button"
          className="garage-owner-sidebar-collapse"
          onClick={() =>
            setSidebarOpen((prev) => !prev)
          }
          aria-label={
            sidebarOpen
              ? "Close sidebar"
              : "Open sidebar"
          }
        >
          {sidebarOpen ? "‹" : "›"}
        </button>

      </aside>


      {/* =====================================================
          MAIN
      ===================================================== */}

      <main className="garage-owner-main">

        {/* =================================================
            HEADER
        ================================================= */}

        <header className="garage-owner-header">

          {/* BREADCRUMB */}

          <div className="garage-owner-breadcrumb">

            <span>
              Auto_Service_Hub
            </span>

            <b>
              /
            </b>

            <strong>
              {getPageName()}
            </strong>

          </div>


          {/* HEADER ACTIONS */}

          <div className="garage-owner-header-actions">

            <div className="garage-owner-role">
              {isDeveloper ? "DEVELOPER" : "GARAGE OWNER"}
            </div>

            <div className="garage-owner-alerts">

              <span className="garage-owner-alert-dot"></span>

              AI insights

            </div>


            {/* SETTINGS / LANDING */}

            <button
              className="garage-owner-settings"
              type="button"
              onClick={() => navigate("/")}
              aria-label="Go to landing page"
            >
              ⚙
            </button>

          </div>

        </header>


        {/* =====================================================
            PAGE CONTENT
        ===================================================== */}

        <section className="garage-owner-content">

          <Outlet />

        </section>

      </main>

    </div>
  );
}

export default GarageOwnerLayout;