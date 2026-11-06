import React, { useCallback, useEffect, useMemo, useState } from "react";
import {
  applyServicePackage,
  createServicePackage,
  deleteServicePackage,
  getServicePackages,
  setServicePackageActive,
  updateServicePackage,
} from "../../services/packageService";
import { getCustomers } from "../../services/customerService";
import { getCustomerVehicles } from "../../services/vehicleService";
import "./PackagesPage.css";

/** Card accent colours the backend accepts (see ServicePackageServiceImpl). */
const PACKAGE_COLORS = ["lime", "blue", "purple", "orange", "green", "red"];
const DEFAULT_COLOR = "lime";

const EMPTY_FORM = {
  name: "",
  code: "",
  price: "",
  duration: "",
  gst: "18",
  discount: "0",
  services: "",
  parts: "",
  color: DEFAULT_COLOR,
  popular: false,
  active: true,
};

const formatPrice = (value) =>
  Number(value ?? 0).toLocaleString("en-IN");

const toNumber = (value) => {
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
};

/** "Engine oil change, Brake check" -> [{ itemName, itemType }] */
const parseItems = (text, itemType) =>
  text
    .split(",")
    .map((entry) => entry.trim())
    .filter(Boolean)
    .map((itemName) => ({ itemName, itemType }));

/**
 * Map a ServicePackageResponseDTO onto the shape the card renders.
 *
 * <p>Items come back as one list tagged SERVICE or PART; the card draws them in
 * two separate places, so they are split here rather than in the JSX.
 */
function toPackageView(servicePackage) {
  const items = servicePackage.items ?? [];

  return {
    id: servicePackage.id,
    name: servicePackage.name ?? "",
    type: servicePackage.type ?? "",
    price: servicePackage.price ?? 0,
    active: servicePackage.active !== false,
    popular: servicePackage.popular === true,
    duration: servicePackage.duration ?? "—",
    gstRate: toNumber(servicePackage.gstRate),
    discountPercentage: toNumber(servicePackage.discountPercentage),
    color: servicePackage.color ?? DEFAULT_COLOR,
    services: items
      .filter((item) => item.itemType === "SERVICE")
      .map((item) => item.itemName),
    includes: items
      .filter((item) => item.itemType === "PART")
      .map((item) => item.itemName),
    raw: servicePackage,
  };
}

function PackagesPage() {
  const [showModal, setShowModal] = useState(false);
  const [editingId, setEditingId] = useState(null);

  const [packages, setPackages] = useState([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [loadError, setLoadError] = useState("");
  const [formError, setFormError] = useState("");
  const [successMessage, setSuccessMessage] = useState("");
  const [notice, setNotice] = useState("");

  const [form, setForm] = useState(EMPTY_FORM);

  /* =====================================================
     APPLY DIALOG STATE
  ===================================================== */

  /** The package card whose "APPLY PACKAGE" button was clicked. Null = closed. */
  const [applyPkg, setApplyPkg] = useState(null);
  const [applyCustomers, setApplyCustomers] = useState([]);
  const [applyCustomerId, setApplyCustomerId] = useState("");
  const [applyVehicles, setApplyVehicles] = useState([]);
  const [applyVehicleId, setApplyVehicleId] = useState("");
  const [applyLoadingCustomers, setApplyLoadingCustomers] = useState(false);
  const [applyLoadingVehicles, setApplyLoadingVehicles] = useState(false);
  const [applySubmitting, setApplySubmitting] = useState(false);
  const [applyError, setApplyError] = useState("");

  /* =====================================================
     LOAD PACKAGES
  ===================================================== */

  const loadPackages = useCallback(async ({ showLoading = true } = {}) => {
    if (showLoading) {
      setIsLoading(true);
    }
    setLoadError("");

    try {
      const fetched = await getServicePackages();
      setPackages(fetched.map(toPackageView));
      return true;
    } catch (error) {
      setLoadError(
        error instanceof Error ? error.message : "Unable to load service packages."
      );
      return false;
    } finally {
      if (showLoading) {
        setIsLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    void loadPackages();
  }, [loadPackages]);

  /* =====================================================
     STATISTICS
  ===================================================== */

  const activeCount = useMemo(
    () => packages.filter((pkg) => pkg.active).length,
    [packages]
  );

  const averagePrice = useMemo(() => {
    if (packages.length === 0) {
      return null;
    }
    const total = packages.reduce((sum, pkg) => sum + Number(pkg.price ?? 0), 0);
    return total / packages.length;
  }, [packages]);

  /* =====================================================
     MODAL
  ===================================================== */

  const openCreateModal = () => {
    setEditingId(null);
    setForm(EMPTY_FORM);
    setFormError("");
    setNotice("");
    setShowModal(true);
  };

  /** Prefill the same modal with the package being edited. */
  const openEditModal = (pkg) => {
    setEditingId(pkg.id);
    setForm({
      name: pkg.name,
      code: pkg.type,
      price: String(pkg.price ?? ""),
      duration: pkg.duration === "—" ? "" : pkg.duration,
      gst: pkg.gstRate == null ? "18" : String(pkg.gstRate),
      discount: pkg.discountPercentage == null ? "0" : String(pkg.discountPercentage),
      services: pkg.services.join(", "),
      parts: pkg.includes.join(", "),
      color: pkg.color ?? DEFAULT_COLOR,
      popular: pkg.popular,
      active: pkg.active,
    });
    setFormError("");
    setNotice("");
    setShowModal(true);
  };

  const closeModal = () => {
    if (isSaving) {
      return;
    }
    setFormError("");
    setShowModal(false);
  };

  /* =====================================================
     FORM
  ===================================================== */

  const handleChange = (e) => {
    const { name, value, type, checked } = e.target;

    setFormError("");

    setForm((prev) => ({
      ...prev,
      [name]: type === "checkbox" ? checked : value,
    }));
  };

  const validateForm = () => {
    const name = form.name.trim();
    const code = form.code.trim();
    const price = toNumber(form.price);
    const discount = toNumber(form.discount);

    if (!name || !code || !form.duration.trim()) {
      return "Package name, package code and duration are required.";
    }
    if (price === null) {
      return "Enter a valid price.";
    }
    if (price <= 0) {
      return "Price must be greater than zero.";
    }
    if (discount === null || discount < 0 || discount > 100) {
      return "Discount must be between 0 and 100.";
    }
    return null;
  };

  const buildRequest = () => {
    const gst = toNumber(form.gst);
    const discount = toNumber(form.discount);

    return {
      name: form.name.trim(),
      // The backend stores the package code in its `type` column.
      type: form.code.trim(),
      price: toNumber(form.price),
      duration: form.duration.trim() || null,
      gstRate: gst,
      discountPercentage: discount === 0 ? 0 : discount,
      color: form.color,
      popular: form.popular,
      active: form.active,
      items: [
        ...parseItems(form.services, "SERVICE"),
        ...parseItems(form.parts, "PART"),
      ],
    };
  };

  /* =====================================================
     SAVE (create or update)
  ===================================================== */

  const handleSubmit = async (e) => {
    e.preventDefault();

    if (isSaving) {
      return;
    }

    const validationError = validateForm();
    if (validationError) {
      setFormError(validationError);
      return;
    }

    setIsSaving(true);
    setFormError("");
    setSuccessMessage("");
    setNotice("");

    try {
      const request = buildRequest();
      const saved =
        editingId === null
          ? await createServicePackage(request)
          : await updateServicePackage(editingId, request);

      setShowModal(false);
      setSuccessMessage(
        `${saved?.name || form.name.trim()} ${
          editingId === null ? "created" : "updated"
        } successfully.`
      );

      await loadPackages({ showLoading: false });
    } catch (error) {
      setFormError(
        error instanceof Error
          ? error.message
          : "Unable to save the service package."
      );
    } finally {
      setIsSaving(false);
    }
  };

  /* =====================================================
     CARD ACTIONS
  ===================================================== */

  /**
   * Open the Apply Package dialog for the given package.
   * Loads the customer list immediately so the picker is ready.
   */
  const handleApply = async (pkg) => {
    setNotice("");
    setSuccessMessage("");
    setApplyPkg(pkg);
    setApplyCustomerId("");
    setApplyVehicles([]);
    setApplyVehicleId("");
    setApplyError("");

    setApplyLoadingCustomers(true);
    try {
      const customers = await getCustomers();
      setApplyCustomers(customers);
    } catch (err) {
      setApplyError(
        err instanceof Error ? err.message : "Unable to load customers."
      );
    } finally {
      setApplyLoadingCustomers(false);
    }
  };

  /** When the advisor picks a customer, load that customer's vehicles. */
  const handleApplyCustomerChange = async (customerId) => {
    setApplyCustomerId(customerId);
    setApplyVehicleId("");
    setApplyVehicles([]);
    setApplyError("");

    if (!customerId) return;

    setApplyLoadingVehicles(true);
    try {
      const vehicles = await getCustomerVehicles(customerId);
      setApplyVehicles(Array.isArray(vehicles) ? vehicles : []);
    } catch (err) {
      setApplyError(
        err instanceof Error ? err.message : "Unable to load vehicles."
      );
    } finally {
      setApplyLoadingVehicles(false);
    }
  };

  const closeApplyDialog = () => {
    if (applySubmitting) return;
    setApplyPkg(null);
    setApplyError("");
  };

  /** Submit the apply-package request to POST /api/v1/package-applications. */
  const handleApplySubmit = async (e) => {
    e.preventDefault();
    if (applySubmitting) return;

    if (!applyCustomerId) {
      setApplyError("Select a customer.");
      return;
    }
    if (!applyVehicleId) {
      setApplyError("Select a vehicle.");
      return;
    }

    setApplySubmitting(true);
    setApplyError("");
    try {
      const result = await applyServicePackage({
        servicePackageId: applyPkg.id,
        customerId: Number(applyCustomerId),
        vehicleId: Number(applyVehicleId),
      });
      setApplyPkg(null);
      setSuccessMessage(
        `"${result.packageName}" applied to ${result.vehicleRegistrationNo || "vehicle"} for ${result.customerName || "customer"} successfully.`
      );
    } catch (err) {
      setApplyError(
        err instanceof Error ? err.message : "Unable to apply the package."
      );
    } finally {
      setApplySubmitting(false);
    }
  };

  const handleToggleActive = async (pkg) => {
    setNotice("");
    setSuccessMessage("");

    try {
      await setServicePackageActive(pkg.raw, !pkg.active);
      await loadPackages({ showLoading: false });
      setSuccessMessage(
        `${pkg.name} ${pkg.active ? "deactivated" : "activated"} successfully.`
      );
    } catch (error) {
      setLoadError(
        error instanceof Error
          ? error.message
          : "Unable to change the package status."
      );
    }
  };

  const handleDelete = async (pkg) => {
    if (!window.confirm(`Delete "${pkg.name}"? This cannot be undone.`)) {
      return;
    }

    setNotice("");
    setSuccessMessage("");

    try {
      await deleteServicePackage(pkg.id);
      await loadPackages({ showLoading: false });
      setSuccessMessage(`${pkg.name} deleted successfully.`);
    } catch (error) {
      setLoadError(
        error instanceof Error ? error.message : "Unable to delete the package."
      );
    }
  };

  /* =====================================================
     RENDER
  ===================================================== */

  return (
    <div className="packages-page">

      {/* =========================
          PAGE HEADER
      ========================= */}
      <div className="packages-page-header">

        <div>
          <div className="packages-eyebrow">
            SERVICE MANAGEMENT
          </div>

          <h1>SERVICE PACKAGES</h1>
        </div>

        <button
          className="packages-new-button"
          type="button"
          onClick={openCreateModal}
        >
          + NEW PACKAGE
        </button>
      </div>

      {/* =========================
          FEEDBACK
      ========================= */}
      {successMessage && (
        <div
          className="packages-feedback packages-feedback-success"
          role="status"
        >
          {successMessage}
        </div>
      )}

      {loadError && (
        <div
          className="packages-feedback packages-feedback-error"
          role="alert"
        >
          <span>
            {loadError}
          </span>

          <button
            type="button"
            onClick={() => void loadPackages()}
          >
            Retry
          </button>
        </div>
      )}

      {notice && (
        <div
          className="packages-feedback packages-feedback-warning"
          role="status"
        >
          <span>
            {notice}
          </span>

          <button
            type="button"
            onClick={() => setNotice("")}
          >
            Dismiss
          </button>
        </div>
      )}

      {/* =========================
          STATISTICS
      ========================= */}
      <div className="packages-stats">

        <div className="packages-stat-card">
          <div className="packages-stat-label">
            ACTIVE PACKAGES
          </div>

          <div className="packages-stat-value">
            {activeCount}
          </div>
        </div>

        {/*
          AMC subscriptions and package revenue have no backend endpoint, so
          these cards report "not tracked" instead of showing invented figures.
        */}
        <div className="packages-stat-card">
          <div className="packages-stat-label">
            AMC CUSTOMERS
          </div>

          <div className="packages-stat-value lime-text">
            —
          </div>

          <div className="packages-stat-description">
            not tracked by the backend
          </div>
        </div>

        <div className="packages-stat-card">
          <div className="packages-stat-label">
            MONTHLY REVENUE
          </div>

          <div className="packages-stat-value">
            —
          </div>

          <div className="packages-stat-description">
            not tracked by the backend
          </div>
        </div>

        <div className="packages-stat-card">
          <div className="packages-stat-label">
            AVG PACKAGE VALUE
          </div>

          <div className="packages-stat-value">
            {averagePrice === null ? "—" : `₹${formatPrice(averagePrice)}`}
          </div>
        </div>

      </div>

      {/* =========================
          PACKAGE CARDS
      ========================= */}
      <div className="packages-grid">

        {isLoading && packages.length === 0 ? (
          <div className="packages-grid-empty">Loading packages…</div>
        ) : packages.length === 0 ? (
          <div className="packages-grid-empty">
            {loadError
              ? "Packages could not be loaded."
              : "No service packages yet. Create one to get started."}
          </div>
        ) : (
          packages.map((pkg) => (
            <div
              className={`package-card package-${pkg.color} ${
                pkg.popular ? "package-popular" : ""
              } ${pkg.active ? "" : "package-inactive"}`}
              key={pkg.id}
            >

              {pkg.popular && (
                <div className="package-popular-badge">
                  POPULAR
                </div>
              )}

              {!pkg.active && (
                <div className="package-inactive-badge">
                  INACTIVE
                </div>
              )}

              {/* CARD TOP */}
              <div className="package-card-top">

                <div className="package-icon">
                  🔧
                </div>

                <div className="package-type">
                  {pkg.type}
                </div>

                <h2>{pkg.name}</h2>

                <div className="package-duration">
                  {pkg.duration}
                </div>

              </div>

              {/* PRICE */}
              <div className="package-price-section">

                <div className="package-price">
                  ₹{formatPrice(pkg.price)}
                </div>

                {pkg.discountPercentage ? (
                  <div className="package-discount">
                    {pkg.discountPercentage}% discount applied
                  </div>
                ) : null}

                {pkg.gstRate != null && (
                  <div className="package-gst">
                    + {pkg.gstRate}% GST
                  </div>
                )}

              </div>

              {/* SERVICES */}
              {pkg.services.length > 0 && (
                <div className="package-services">

                  {pkg.services.map((service, index) => (
                    <div
                      className="package-service"
                      key={`${pkg.id}-service-${index}`}
                    >
                      <span className="package-check">
                        ✓
                      </span>

                      <span>{service}</span>
                    </div>
                  ))}

                </div>
              )}

              {/* INCLUDES */}
              {pkg.includes.length > 0 && (
                <div className="package-includes">

                  <div className="package-includes-title">
                    INCLUDES
                  </div>

                  <div className="package-tags">

                    {pkg.includes.map((item, index) => (
                      <span
                        className="package-tag"
                        key={`${pkg.id}-include-${index}`}
                      >
                        {item}
                      </span>
                    ))}

                  </div>

                </div>
              )}

              {/* ACTIONS */}
              <div className="package-actions">

                <button
                  type="button"
                  className="package-apply-button"
                  onClick={() => handleApply(pkg)}
                >
                  APPLY PACKAGE
                </button>

                <button
                  type="button"
                  className="package-edit-button"
                  onClick={() => openEditModal(pkg)}
                >
                  Edit
                </button>

                <button
                  type="button"
                  className="package-edit-button package-action-text"
                  onClick={() => void handleToggleActive(pkg)}
                >
                  {pkg.active ? "Deactivate" : "Activate"}
                </button>

                <button
                  type="button"
                  className="package-edit-button package-action-text package-delete-button"
                  onClick={() => void handleDelete(pkg)}
                >
                  Delete
                </button>

              </div>

            </div>
          ))
        )}

      </div>

      {/* =========================
          NEW / EDIT PACKAGE MODAL
      ========================= */}
      {showModal && (
        <div
          className="package-modal-overlay"
          onMouseDown={(e) => {
            if (e.target === e.currentTarget) {
              closeModal();
            }
          }}
        >

          <div className="package-modal">

            {/* MODAL HEADER */}
            <div className="package-modal-header">

              <h2>
                {editingId === null ? "NEW SERVICE PACKAGE" : "EDIT SERVICE PACKAGE"}
              </h2>

              <button
                type="button"
                className="package-modal-close"
                onClick={closeModal}
              >
                ×
              </button>

            </div>

            {/* FORM */}
            <form
              className="package-form"
              onSubmit={handleSubmit}
            >

              {formError && (
                <div
                  className="packages-feedback packages-feedback-error full"
                  role="alert"
                >
                  <span>
                    {formError}
                  </span>
                </div>
              )}

              {/* PACKAGE NAME */}
              <div className="package-form-group full">
                <label>PACKAGE NAME</label>

                <input
                  type="text"
                  name="name"
                  value={form.name}
                  onChange={handleChange}
                  placeholder="Premium Plus Service"
                  required
                />
              </div>

              {/* PACKAGE CODE */}
              <div className="package-form-group full">
                <label>PACKAGE CODE</label>

                <input
                  type="text"
                  name="code"
                  value={form.code}
                  onChange={handleChange}
                  placeholder="PREM-PLUS"
                  required
                />
              </div>

              {/* PRICE */}
              <div className="package-form-group">
                <label>PRICE (₹)</label>

                <input
                  type="number"
                  name="price"
                  min="0"
                  step="0.01"
                  value={form.price}
                  onChange={handleChange}
                  placeholder="8999"
                  required
                />
              </div>

              {/* DURATION */}
              <div className="package-form-group">
                <label>DURATION / VALIDITY</label>

                <input
                  type="text"
                  name="duration"
                  value={form.duration}
                  onChange={handleChange}
                  placeholder="4–5 hrs / 6 months"
                  required
                />
              </div>

              {/* GST */}
              <div className="package-form-group">
                <label>GST RATE (%)</label>

                <select
                  name="gst"
                  value={form.gst}
                  onChange={handleChange}
                >
                  <option value="5">5</option>
                  <option value="12">12</option>
                  <option value="18">18</option>
                  <option value="28">28</option>
                </select>
              </div>

              {/* DISCOUNT */}
              <div className="package-form-group">
                <label>DISCOUNT (%)</label>

                <input
                  type="number"
                  name="discount"
                  min="0"
                  max="100"
                  value={form.discount}
                  onChange={handleChange}
                  placeholder="0"
                />
              </div>

              {/* SERVICES */}
              <div className="package-form-group full">
                <label>INCLUDED SERVICES</label>

                <textarea
                  name="services"
                  value={form.services}
                  onChange={handleChange}
                  placeholder="Engine oil change, Oil filter, AC filter check, Brake inspection..."
                />
              </div>

              {/* PARTS */}
              <div className="package-form-group full">
                <label>INCLUDED PARTS / CONSUMABLES</label>

                <textarea
                  name="parts"
                  value={form.parts}
                  onChange={handleChange}
                  placeholder="Engine Oil 5L, Oil Filter, AC Filter..."
                />
              </div>

              {/* COLOURS */}
              <div className="package-form-group full">

                <label>
                  PACKAGE COLOUR (FOR UI)
                </label>

                <div className="package-colors">

                  {PACKAGE_COLORS.map((color) => (
                    <button
                      key={color}
                      type="button"
                      className={`package-color-option ${color} ${
                        form.color === color
                          ? "selected"
                          : ""
                      }`}
                      onClick={() =>
                        setForm((prev) => ({
                          ...prev,
                          color,
                        }))
                      }
                      aria-label={color}
                    />
                  ))}

                </div>

              </div>

              {/* POPULAR */}
              <div className="package-popular-option full">

                <label className="package-checkbox">

                  <input
                    type="checkbox"
                    name="popular"
                    checked={form.popular}
                    onChange={handleChange}
                  />

                  <span>
                    Show "Popular" badge on this package
                  </span>

                </label>

              </div>

              {/* ACTIVE (edit only — a new package is active by default) */}
              {editingId !== null && (
                <div className="package-popular-option full">

                  <label className="package-checkbox">

                    <input
                      type="checkbox"
                      name="active"
                      checked={form.active}
                      onChange={handleChange}
                    />

                    <span>
                      Active — offer this package to customers
                    </span>

                  </label>

                </div>
              )}

              {/* BUTTONS */}
              <div className="package-modal-actions full">

                <button
                  type="submit"
                  className="package-create-button"
                  disabled={isSaving}
                >
                  {isSaving
                    ? "SAVING…"
                    : editingId === null
                    ? "CREATE PACKAGE"
                    : "SAVE CHANGES"}
                </button>

                <button
                  type="button"
                  className="package-cancel-button"
                  onClick={closeModal}
                  disabled={isSaving}
                >
                  Cancel
                </button>

              </div>

            </form>

          </div>

        </div>
      )}

      {/* =========================
          APPLY PACKAGE DIALOG
      ========================= */}
      {applyPkg && (
        <div
          className="package-modal-overlay"
          onMouseDown={(e) => {
            if (e.target === e.currentTarget) closeApplyDialog();
          }}
        >
          <div className="package-modal package-apply-dialog">

            <div className="package-modal-header">
              <h2>APPLY PACKAGE — {applyPkg.name.toUpperCase()}</h2>
              <button
                type="button"
                className="package-modal-close"
                onClick={closeApplyDialog}
                disabled={applySubmitting}
              >
                ×
              </button>
            </div>

            <form
              className="package-apply-form"
              onSubmit={handleApplySubmit}
            >

              {applyError && (
                <div
                  className="packages-feedback packages-feedback-error"
                  role="alert"
                >
                  <span>{applyError}</span>
                </div>
              )}

              {/* CUSTOMER */}
              <div className="package-form-group full">
                <label htmlFor="apply-customer-select">CUSTOMER</label>
                <select
                  id="apply-customer-select"
                  value={applyCustomerId}
                  onChange={(e) => void handleApplyCustomerChange(e.target.value)}
                  disabled={applyLoadingCustomers || applySubmitting}
                  required
                >
                  <option value="">
                    {applyLoadingCustomers ? "Loading customers…" : "— select customer —"}
                  </option>
                  {applyCustomers.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name}{c.phone ? ` · ${c.phone}` : ""}
                    </option>
                  ))}
                </select>
              </div>

              {/* VEHICLE */}
              <div className="package-form-group full">
                <label htmlFor="apply-vehicle-select">VEHICLE</label>
                <select
                  id="apply-vehicle-select"
                  value={applyVehicleId}
                  onChange={(e) => setApplyVehicleId(e.target.value)}
                  disabled={!applyCustomerId || applyLoadingVehicles || applySubmitting}
                  required
                >
                  <option value="">
                    {!applyCustomerId
                      ? "— select a customer first —"
                      : applyLoadingVehicles
                      ? "Loading vehicles…"
                      : applyVehicles.length === 0
                      ? "No vehicles for this customer"
                      : "— select vehicle —"}
                  </option>
                  {applyVehicles.map((v) => (
                    <option key={v.id} value={v.id}>
                      {v.registrationNo || `${v.make ?? ""} ${v.model ?? ""}`}
                      {v.make && v.registrationNo ? ` — ${v.make} ${v.model ?? ""}` : ""}
                    </option>
                  ))}
                </select>
              </div>

              {/* PRICE SUMMARY */}
              <div className="package-apply-summary full">
                <span className="package-apply-summary-label">Package price</span>
                <span className="package-apply-summary-value">
                  ₹{formatPrice(applyPkg.price)}
                  {applyPkg.gstRate != null ? ` + ${applyPkg.gstRate}% GST` : ""}
                  {applyPkg.discountPercentage ? ` (${applyPkg.discountPercentage}% discount)` : ""}
                </span>
              </div>

              {/* ACTIONS */}
              <div className="package-modal-actions full">
                <button
                  type="submit"
                  className="package-create-button"
                  disabled={applySubmitting || applyLoadingCustomers || !applyCustomerId || !applyVehicleId}
                >
                  {applySubmitting ? "APPLYING…" : "CONFIRM & APPLY"}
                </button>
                <button
                  type="button"
                  className="package-cancel-button"
                  onClick={closeApplyDialog}
                  disabled={applySubmitting}
                >
                  Cancel
                </button>
              </div>

            </form>

          </div>
        </div>
      )}

    </div>
  );
}

export default PackagesPage;