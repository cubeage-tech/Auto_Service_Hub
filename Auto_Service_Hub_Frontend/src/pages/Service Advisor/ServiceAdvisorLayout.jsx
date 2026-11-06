import React from "react";
import { NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import "./ServiceAdvisor.css";
import "./ServiceAdvisorTheme.css";

const nav = [
  ["▦", "Dashboard", "/service-advisor"],
  ["♙", "Customers", "/service-advisor/customers"],
  ["▱", "Vehicles", "/service-advisor/vehicles"],
  ["□", "Appointments", "/service-advisor/appointments"],
  ["◇", "Job Cards", "/service-advisor/jobcards"],
  ["♢", "Packages", "/service-advisor/packages"],
];

export default function ServiceAdvisorLayout() {
  const location = useLocation();
  const navigate = useNavigate();

  const currentPage = nav.find(([, , to]) =>
    to === "/service-advisor"
      ? location.pathname === to
      : location.pathname.startsWith(to)
  )?.[1] || "Dashboard";

  return (
    <div className="sa-shell">
      <aside className="sa-sidebar">
        <div className="sa-logo-row">
          <div className="sa-logo-icon">◆</div>
          <div className="sa-logo">Auto_Service_Hub</div>
        </div>

        <nav className="sa-nav">
          {nav.map(([icon, label, to]) => (
            <NavLink
              key={label}
              to={to}
              end={label === "Dashboard"}
              className={({ isActive }) => `sa-nav-item ${isActive ? "active" : ""}`}
            >
              <span className="sa-nav-icon">{icon}</span>
              <span>{label}</span>
            </NavLink>
          ))}
        </nav>

        <div className="sa-sidebar-bottom">
          <button onClick={() => navigate("/modules")} className="sa-collapse">‹</button>
        </div>
      </aside>

      <main className="sa-main">
        <header className="sa-topbar">
          <div className="sa-breadcrumb">
            <span>Auto_Service_Hub</span><b>/</b><strong>{currentPage}</strong>
          </div>
          <div className="sa-top-actions">
            <span className="sa-role">SERVICE ADVISOR</span>
            <span className="sa-alert-count"><i /> AI insights</span>
            <button className="sa-avatar">▣</button>
          </div>
        </header>
        <Outlet />
      </main>
    </div>
  );
}
