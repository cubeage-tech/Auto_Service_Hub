import React, { useContext } from "react";
import { AuthProvider } from "./context/AuthContext";
import {
  BrowserRouter,
  Navigate,
  Outlet,
  Routes,
  Route,
} from "react-router-dom";
import { useAuth } from "./hooks/useAuth";
import { AuthContext } from "./context/AuthContext";

/* =========================
   LANDING
========================= */
import LandingPage from "./pages/landing/LandingPage";

/* =========================
   MODULE SELECTION
========================= */
import ModulePage from "./pages/auth/ModulePage";
import AccessRequestPage from "./pages/auth/AccessRequestPage";
import AdminLayout from "./pages/admin/AdminLayout";
import AdminDashboard from "./pages/admin/AdminDashboard";

/* =========================
   MECHANIC
========================= */
import MechanicLogin from "./pages/mechanic/MechanicLogin";
import MechanicLayout from "./pages/mechanic/MechanicLayout";
import DashboardPage from "./pages/dashboard/DashboardPage";

/* =========================
   INVENTORY
========================= */
import InventoryLogin from "./pages/inventory/InventoryLogin";
import InventoryLayout from "./pages/inventory/InventoryLayout";
import InventoryDashboard from "./pages/inventory/InventoryDashboard";
import InventoryPage from "./pages/inventory/InventoryPage";

/* =========================
   JOB CARDS
========================= */
import JobCardListPage from "./pages/jobcards/JobCardListPage";
import JobCardDetailsPage from "./pages/jobcards/JobCardDetailsPage";

/* =========================
   CUSTOMERS
========================= */
import CustomerLogin from "./pages/customers/CustomerLogin";
import CustomerDashboard from "./pages/customers/Dashboard";
import Appointments from "./pages/customers/Appointments";

/* =========================
   BILLING
========================= */
import BillingLogin from "./pages/billing/BillingLogin";
import BillingPage from "./pages/billing/BillingPage";

/* =========================
   GARAGE OWNER LOGIN
========================= */
import GarageOwnerLogin from "./pages/garage-owner/GarageOwnerLogin";

/* =========================
   SERVICE ADVISOR LOGIN
========================= */
import ServiceAdvisorLogin from "./pages/Service Advisor/ServiceAdvisorLogin";
import ServiceAdvisorLayout from "./pages/Service Advisor/ServiceAdvisorLayout";

/* =========================
   DEVELOPER LOGIN
========================= */
import DeveloperLogin from "./pages/Developer/DeveloperLogin";

/* =========================
   GARAGE OWNER LAYOUT
========================= */
import GarageOwnerLayout from "./pages/garage-owner/GarageOwnerLayout";

/* =========================
   GARAGE OWNER PAGES
========================= */
import GarageOwnerDashboard from "./pages/garage-owner/GarageOwnerDashboard";
import GarageOwnerCustomers from "./pages/garage-owner/GarageOwnerCustomers";
import GarageOwnerVehicles from "./pages/garage-owner/GarageOwnerVehicles";
import GarageOwnerAppointments from "./pages/garage-owner/GarageOwnerAppointments";
import GarageOwnerJobCards from "./pages/garage-owner/GarageOwnerJobCards";
import GarageOwnerMechanics from "./pages/garage-owner/GarageOwnerMechanics";
import GarageOwnerInventory from "./pages/garage-owner/GarageOwnerInventory";
import GarageOwnerBilling from "./pages/garage-owner/GarageOwnerBilling";
import GarageOwnerFollowup from "./pages/garage-owner/GarageOwnerFollowup";
import GarageOwnerAIHub from "./pages/garage-owner/GarageOwnerAIHub";
import GarageOwnerReports from "./pages/garage-owner/GarageOwnerReports";
import GarageOwnerUsers from "./pages/garage-owner/GarageOwnerUsers";

/* =========================
   PACKAGES
========================= */
import PackagesPage from "./pages/packages/PackagesPage";

/* =========================
   SERVICE ADVISOR PAGES
   Aliased with a ServiceAdvisor prefix because Appointments and
   PackagesPage are already imported above from other modules.
========================= */
import ServiceAdvisorDashboard from "./pages/Service Advisor/Dashboard";
import ServiceAdvisorCustomers from "./pages/Service Advisor/Customers";
import ServiceAdvisorVehicles from "./pages/Service Advisor/Vehicles";
import ServiceAdvisorAppointments from "./pages/Service Advisor/Appointments";
import ServiceAdvisorJobCards from "./pages/Service Advisor/JobCards";
import ServiceAdvisorPackages from "./pages/Service Advisor/Packages";


/**
 * Users and Roles is Owner-only (SRS 13). UserController already answers 403 to
 * anyone else, and the sidebar hides the entry; this stops a typed URL from
 * rendering the screen in the meantime. Anything that is not an owner is sent
 * back to the dashboard of the module they are in, so the two guard sites below
 * each pass their own target.
 */
function OwnerOnlyRoute({ to, children }) {
  const { user } = useAuth();
  const role = String(user?.role || "").replace(/^ROLE_/, "").toUpperCase();

  if (role === "OWNER" || role === "GARAGE_OWNER") {
    return children;
  }

  return <Navigate to={to} replace />;
}

const homeByRole = {
  ADMIN: "/admin",
  OWNER: "/garage-owner/dashboard",
  MANAGER: "/garage-owner/dashboard",
  SERVICE_ADVISOR: "/service-advisor",
  MECHANIC: "/mechanic-dashboard",
  INVENTORY_MANAGER: "/inventory-dashboard",
  BILLING_USER: "/billing/dashboard",
};

function RequireRole({ roles }) {
  const { user, isAuthenticated } = useContext(AuthContext);
  if (!isAuthenticated) return <Navigate to="/modules" replace />;
  return roles.includes(user?.role)
    ? <Outlet />
    : <Navigate to={homeByRole[user?.role] || "/modules"} replace />;
}

function PublicOnly({ children, roleRedirectOverrides = {} }) {
  const { user, isAuthenticated } = useContext(AuthContext);
  const roleRedirects = { ...homeByRole, ...roleRedirectOverrides };
  return isAuthenticated
    ? <Navigate to={roleRedirects[user?.role] || "/"} replace />
    : children;
}

function App() {
  return (
    <AuthProvider>
      <BrowserRouter>

        <Routes>

        {/* =====================================================
            LANDING
        ===================================================== */}

        <Route
          path="/"
          element={<LandingPage />}
        />


        {/* =====================================================
            MODULE SELECTION
        ===================================================== */}

        <Route
          path="/modules"
          element={<ModulePage />}
        />
        <Route path="/access-request" element={<PublicOnly><AccessRequestPage /></PublicOnly>} />


        {/* =====================================================
            MECHANIC
        ===================================================== */}

        <Route
          path="/mechanic"
          element={<MechanicLogin />}
        />

        <Route element={<RequireRole roles={["MECHANIC"]} />}>
        <Route element={<MechanicLayout />}>

          <Route
            path="/mechanic-dashboard"
            element={<DashboardPage />}
          />

          <Route
            path="/job-cards"
            element={<JobCardListPage />}
          />

          <Route
            path="/job-cards/create"
            element={<JobCardListPage />}
          />

          <Route
            path="/job-cards/:jobId"
            element={<JobCardDetailsPage />}
          />

        </Route>
        </Route>


        {/* =====================================================
            INVENTORY LOGIN
        ===================================================== */}

        <Route
          path="/inventory-login"
          element={<PublicOnly><InventoryLogin /></PublicOnly>}
        />


        {/* =====================================================
            INVENTORY MODULE
        ===================================================== */}

        <Route element={<RequireRole roles={["INVENTORY_MANAGER"]} />}>
        <Route element={<InventoryLayout />}>

          <Route
            path="/inventory-dashboard"
            element={<InventoryDashboard />}
          />

          <Route
            path="/inventory"
            element={<InventoryPage />}
          />

        </Route>
        </Route>

        {/* =====================================================
            CUSTOMERS
        ===================================================== */}

        <Route path="/customers" element={<CustomerLogin />} />
        <Route path="/dashboard" element={<CustomerDashboard />} />
        <Route path="/customer-dashboard" element={<CustomerDashboard />} />
        <Route path="/appointments" element={<Appointments />} />


        {/* =====================================================
            BILLING
        ===================================================== */}

        <Route
          path="/billing"
          element={<PublicOnly><BillingLogin /></PublicOnly>}
        />

        <Route element={<RequireRole roles={["BILLING_USER"]} />}>
          <Route path="/billing/dashboard" element={<BillingPage />} />
        </Route>


        {/* =====================================================
            GARAGE OWNER LOGIN
        ===================================================== */}

        <Route
          path="/garage-owner-login"
          element={<PublicOnly><GarageOwnerLogin /></PublicOnly>}
        />


        {/* =====================================================
            SERVICE ADVISOR LOGIN
        ===================================================== */}

        <Route
          path="/service-advisor-login"
          element={<PublicOnly><ServiceAdvisorLogin /></PublicOnly>}
        />

        <Route path="/service-advisor" element={<RequireRole roles={["SERVICE_ADVISOR"]} />}>
          <Route element={<ServiceAdvisorLayout />}>
            <Route index element={<GarageOwnerDashboard />} />
            <Route path="customers" element={<GarageOwnerCustomers />} />
            <Route path="vehicles" element={<GarageOwnerVehicles />} />
            <Route path="appointments" element={<GarageOwnerAppointments />} />
            <Route path="jobcards" element={<GarageOwnerJobCards />} />
            <Route path="packages" element={<PackagesPage />} />
          </Route>
        </Route>


        {/* =====================================================
            SERVICE ADVISOR MODULE
        ===================================================== */}

        <Route
          path="/service-advisor"
          element={<ServiceAdvisorLayout />}
        >

          <Route
            index
            element={<ServiceAdvisorDashboard />}
          />

          <Route
            path="customers"
            element={<ServiceAdvisorCustomers />}
          />

          <Route
            path="vehicles"
            element={<ServiceAdvisorVehicles />}
          />

          <Route
            path="appointments"
            element={<ServiceAdvisorAppointments />}
          />

          <Route
            path="jobcards"
            element={<ServiceAdvisorJobCards />}
          />

          <Route
            path="packages"
            element={<ServiceAdvisorPackages />}
          />

        </Route>


        {/* =====================================================
            DEVELOPER LOGIN
        ===================================================== */}

        <Route
          path="/developer-login"
          element={<PublicOnly roleRedirectOverrides={{ ADMIN: "/developer" }}><DeveloperLogin /></PublicOnly>}
        />
        <Route path="/admin-login" element={<PublicOnly><DeveloperLogin /></PublicOnly>} />


        {/* =====================================================
            GARAGE OWNER MODULE
        ===================================================== */}

        <Route
          path="/garage-owner"
          element={<RequireRole roles={["OWNER", "MANAGER"]} />}
        >
          <Route element={<GarageOwnerLayout />}>

          <Route
            index
            element={<GarageOwnerDashboard />}
          />

          <Route
            path="dashboard"
            element={<GarageOwnerDashboard />}
          />

          <Route
            path="customers"
            element={<GarageOwnerCustomers />}
          />

          <Route
            path="vehicles"
            element={<GarageOwnerVehicles />}
          />

          <Route
            path="appointments"
            element={<GarageOwnerAppointments />}
          />

          <Route
            path="jobcards"
            element={<GarageOwnerJobCards />}
          />
          <Route
            path="jobcards/:jobId"
            element={<GarageOwnerJobCards />}
          />

          <Route
            path="mechanics"
            element={<GarageOwnerMechanics />}
          />

          <Route
            path="inventory"
            element={<GarageOwnerInventory />}
          />

          <Route
            path="packages"
            element={<PackagesPage />}
          />

          <Route
            path="billing"
            element={<GarageOwnerBilling />}
          />

          <Route
            path="follow-up"
            element={<GarageOwnerFollowup />}
          />

          <Route
            path="ai-hub"
            element={<GarageOwnerAIHub />}
          />

          <Route
            path="reports"
            element={<GarageOwnerReports />}
          />

          <Route
            path="users"
            element={
              <OwnerOnlyRoute to="/garage-owner/dashboard">
                <GarageOwnerUsers />
              </OwnerOnlyRoute>
            }
          />

          </Route>
        </Route>

        <Route path="/admin" element={<RequireRole roles={["ADMIN"]} />}>
          <Route element={<AdminLayout />}>
            <Route index element={<AdminDashboard />} />
            <Route path="customers" element={<GarageOwnerCustomers />} />
          </Route>
        </Route>

        {/* =====================================================
            DEVELOPER MODULE
            REUSES GARAGE OWNER COMPONENTS
        ===================================================== */}

        <Route path="/developer" element={<RequireRole roles={["ADMIN"]} />}>
          <Route element={<GarageOwnerLayout />}>

          {/* Developer Dashboard */}

          <Route
            index
            element={<GarageOwnerDashboard />}
          />

          <Route
            path="dashboard"
            element={<GarageOwnerDashboard />}
          />


          {/* Developer Customers */}

          <Route
            path="customers"
            element={<GarageOwnerCustomers />}
          />


          {/* Developer Vehicles */}

          <Route
            path="vehicles"
            element={<GarageOwnerVehicles />}
          />


          {/* Developer Appointments */}

          <Route
            path="appointments"
            element={<GarageOwnerAppointments />}
          />


          {/* Developer Job Cards */}

          <Route
            path="jobcards"
            element={<GarageOwnerJobCards />}
          />
          <Route
            path="jobcards/:jobId"
            element={<GarageOwnerJobCards />}
          />


          {/* Developer Mechanics */}

          <Route
            path="mechanics"
            element={<GarageOwnerMechanics />}
          />


          {/* Developer Inventory */}

          <Route
            path="inventory"
            element={<GarageOwnerInventory />}
          />


          {/* Developer Packages */}

          <Route
            path="packages"
            element={<PackagesPage />}
          />


          {/* Developer Billing */}

          <Route
            path="billing"
            element={<GarageOwnerBilling />}
          />


          {/* Developer Follow Up */}

          <Route
            path="follow-up"
            element={<GarageOwnerFollowup />}
          />


          {/* Developer AI Hub */}

          <Route
            path="ai-hub"
            element={<GarageOwnerAIHub />}
          />


          {/* Developer Reports */}

          <Route
            path="reports"
            element={<GarageOwnerReports />}
          />


          {/* Developer User Management */}

          <Route
            path="users"
            element={
              <OwnerOnlyRoute to="/developer/dashboard">
                <GarageOwnerUsers />
              </OwnerOnlyRoute>
            }
          />

          </Route>
        </Route>

        </Routes>

      </BrowserRouter>
    </AuthProvider>
  );
}

export default App;