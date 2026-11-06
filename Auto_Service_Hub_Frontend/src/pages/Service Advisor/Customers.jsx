import React, { useEffect, useMemo, useState } from "react";

import { createCustomer, getCustomers } from "../../services/customerService";

import "./ServiceAdvisor.css";


const LOYALTY_TIERS = ["Bronze", "Silver", "Gold", "Platinum"];

const EMPTY_FORM = {
  firstName: "",
  lastName: "",
  phone: "",
  email: "",
  address: "",
  city: "",
  pincode: "",
  loyaltyTier: "Bronze",
  notes: "",
};

/* Mirrors the tier-to-badge mapping already used by the table. */
const loyaltyBadgeClass = (tier) => {
  if (tier === "Gold" || tier === "Bronze") return "status-inspection";
  if (tier === "Platinum" || tier === "Silver") return "status-received";
  return "";
};


/* =========================================================
   DASHBOARD-SAFE PLACEMARKER

   CustomerResponseDTO is the only shape the list endpoint returns
   (id, name, phone, email, address, status, loyaltyTier, notes,
   createdAt, updatedAt). It carries no vehicle count, visit count,
   last-visit date or outstanding balance, so those cells render a
   dash. Nothing here derives a value the backend did not send.
   ========================================================= */

const NOT_PROVIDED = "—";


export default function Customers() {
  const [list, setList] = useState({ status: "loading", data: [], error: "" });
  const [refreshKey, setRefreshKey] = useState(0);

  const [q, setQ] = useState("");

  const [showForm, setShowForm] = useState(false);
  const [formData, setFormData] = useState(EMPTY_FORM);
  const [isCreating, setIsCreating] = useState(false);
  const [createError, setCreateError] = useState("");
  const [successMessage, setSuccessMessage] = useState("");

  /* Initial load and every refresh re-read the list from the backend.
     State is only written after the await resolves, so React does not
     treat this as a synchronous setState-in-effect. */
  useEffect(() => {
    let cancelled = false;

    const load = async () => {
      try {
        const customers = await getCustomers();
        if (!cancelled) {
          setList({
            status: "success",
            data: Array.isArray(customers) ? customers : [],
            error: "",
          });
        }
      } catch (error) {
        if (!cancelled) {
          setList({
            status: "error",
            data: [],
            error:
              error instanceof Error
                ? error.message
                : "Unable to load customers.",
          });
        }
      }
    };

    load();

    return () => {
      cancelled = true;
    };
  }, [refreshKey]);

  const handleRetry = () => {
    setList({ status: "loading", data: [], error: "" });
    setRefreshKey((value) => value + 1);
  };

  /* Search runs across the real record fields only. */
  const customers = useMemo(() => {
    const needle = q.trim().toLowerCase();
    if (!needle) return list.data;
    return list.data.filter((customer) =>
      [customer.id, customer.name, customer.phone, customer.email, customer.loyaltyTier]
        .map((value) => (value === null || value === undefined ? "" : String(value)))
        .join(" ")
        .toLowerCase()
        .includes(needle)
    );
  }, [list.data, q]);

  const updateField = (field) => (event) =>
    setFormData((current) => ({ ...current, [field]: event.target.value }));

  /* Client-side checks mirror the DTO constraints so an avoidable round trip
     is not made. The backend remains the authority — its error is what the
     user sees if anything still fails. */
  const validate = () => {
    if (!formData.firstName.trim()) return "First name is required.";
    if (!formData.lastName.trim()) return "Last name is required.";
    if (!formData.phone.trim()) return "Phone number is required.";

    const email = formData.email.trim();
    if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      return "Enter a valid email address, or leave the field blank.";
    }
    if (!LOYALTY_TIERS.includes(formData.loyaltyTier)) {
      return "Loyalty tier must be Bronze, Silver, Gold, or Platinum.";
    }
    if (formData.notes.length > 2000) {
      return "Notes cannot exceed 2000 characters.";
    }
    return "";
  };

  const openForm = () => {
    setFormData(EMPTY_FORM);
    setCreateError("");
    setSuccessMessage("");
    setShowForm(true);
  };

  const closeForm = () => {
    if (!isCreating) {
      setShowForm(false);
      setCreateError("");
    }
  };

  const handleCreateCustomer = async (e) => {
    e.preventDefault();

    /* Guards against a double submit while a request is in flight. */
    if (isCreating) return;

    const validationError = validate();
    if (validationError) {
      setCreateError(validationError);
      return;
    }

    setIsCreating(true);
    setCreateError("");

    /* CustomerRequestDTO has no firstName, lastName, city or pincode field.
       Those inputs are composed into the two fields the backend does accept,
       exactly as the Garage Owner customer form does. */
    const address = [formData.address, formData.city, formData.pincode]
      .map((part) => part.trim())
      .filter(Boolean)
      .join(", ");

    const composedName = `${formData.firstName.trim()} ${formData.lastName.trim()}`;

    try {
      await createCustomer({
        name: composedName,
        phone: formData.phone.trim(),
        email: formData.email.trim(),
        address,
        loyaltyTier: formData.loyaltyTier,
        notes: formData.notes.trim(),
      });

      /* Re-read the list rather than inserting the response by hand, so the
         new customer appears exactly once and only with backend data. */
      setQ("");
      setSuccessMessage(`${composedName} created successfully.`);
      setShowForm(false);
      setFormData(EMPTY_FORM);
      setRefreshKey((value) => value + 1);

    } catch (error) {
      /* The form stays open so the entered values are not lost. */
      setCreateError(
        error instanceof Error
          ? error.message
          : "Unable to create the customer."
      );
    } finally {
      setIsCreating(false);
    }
  };


  return (
    <div className="sa-page">

      <div className="sa-page-head">
        <div>
          <h1 className="sa-title">CUSTOMERS</h1>
        </div>
        <button
          type="button"
          className="sa-primary"
          onClick={openForm}
          disabled={isCreating}
        >
          + NEW CUSTOMER
        </button>
      </div>


      {successMessage && (
        <div className="sa-success" role="status">
          {successMessage}
        </div>
      )}


      <div className="sa-toolbar">
        <div style={{ position: "relative" }}>
          <input
            className="sa-search"
            placeholder="Search by name or phone..."
            value={q}
            onChange={(e) => setQ(e.target.value)}
          />
        </div>
      </div>


      <div className="sa-wide-table">
        <table className="sa-table">
          <thead>
            <tr>
              <th>ID</th>
              <th>Name</th>
              <th>Phone</th>
              <th>Email</th>
              <th>Vehicles</th>
              <th>Visits</th>
              <th>Last Visit</th>
              <th>Loyalty</th>
              <th>Balance</th>
            </tr>
          </thead>
          <tbody>
            {list.status === "loading" ? (
              <tr>
                <td colSpan={9}>Loading customers…</td>
              </tr>
            ) : list.status === "error" ? (
              <tr>
                <td colSpan={9}>
                  <div className="sa-list-error" role="alert">
                    <span>{list.error}</span>
                    <button
                      type="button"
                      className="sa-retry"
                      onClick={handleRetry}
                    >
                      Retry
                    </button>
                  </div>
                </td>
              </tr>
            ) : customers.length === 0 ? (
              <tr>
                <td colSpan={9}>
                  {list.data.length === 0
                    ? "No customers have been added yet."
                    : "No customers match your search."}
                </td>
              </tr>
            ) : (
              customers.map((customer) => (
                <tr key={customer.id}>
                  <td className="sa-id">{customer.id ?? NOT_PROVIDED}</td>
                  <td><strong>{customer.name || NOT_PROVIDED}</strong></td>
                  <td><strong>{customer.phone || NOT_PROVIDED}</strong></td>
                  <td>{customer.email || NOT_PROVIDED}</td>
                  <td>{NOT_PROVIDED}</td>
                  <td>{NOT_PROVIDED}</td>
                  <td>{NOT_PROVIDED}</td>
                  <td>
                    {customer.loyaltyTier ? (
                      <span className={`sa-status ${loyaltyBadgeClass(customer.loyaltyTier)}`}>
                        {customer.loyaltyTier}
                      </span>
                    ) : (
                      NOT_PROVIDED
                    )}
                  </td>
                  <td>{NOT_PROVIDED}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>


      {/* ── NEW CUSTOMER MODAL ── */}

      {showForm && (
        <div
          className="sa-modal-overlay"
          role="presentation"
          onClick={(e) => {
            /* Only a click on the overlay itself closes; clicks inside the
               dialog are ignored so the form is never dismissed by accident. */
            if (e.target === e.currentTarget) closeForm();
          }}
        >
          <div
            className="sa-modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="sa-new-customer-title"
          >
            <div className="sa-modal-header">
              <h2 className="sa-modal-title" id="sa-new-customer-title">
                NEW CUSTOMER
              </h2>
              <button
                type="button"
                className="sa-modal-close"
                onClick={closeForm}
                disabled={isCreating}
                aria-label="Close"
              >
                ×
              </button>
            </div>

            <form
              className="sa-modal-form"
              onSubmit={handleCreateCustomer}
              noValidate
            >

              <div className="sa-modal-row">
                <label className="sa-modal-field">
                  <span>First Name *</span>
                  <input
                    className="sa-modal-input"
                    value={formData.firstName}
                    onChange={updateField("firstName")}
                    disabled={isCreating}
                    maxLength={255}
                    autoComplete="given-name"
                  />
                </label>

                <label className="sa-modal-field">
                  <span>Last Name *</span>
                  <input
                    className="sa-modal-input"
                    value={formData.lastName}
                    onChange={updateField("lastName")}
                    disabled={isCreating}
                    maxLength={255}
                    autoComplete="family-name"
                  />
                </label>
              </div>

              <label className="sa-modal-field">
                <span>Phone Number *</span>
                <input
                  className="sa-modal-input"
                  type="tel"
                  value={formData.phone}
                  onChange={updateField("phone")}
                  disabled={isCreating}
                  maxLength={255}
                  autoComplete="tel"
                />
              </label>

              <label className="sa-modal-field">
                <span>Email Address</span>
                <input
                  className="sa-modal-input"
                  type="email"
                  value={formData.email}
                  onChange={updateField("email")}
                  disabled={isCreating}
                  maxLength={255}
                  autoComplete="email"
                />
              </label>

              <label className="sa-modal-field">
                <span>Address</span>
                <input
                  className="sa-modal-input"
                  value={formData.address}
                  onChange={updateField("address")}
                  disabled={isCreating}
                  maxLength={255}
                  autoComplete="street-address"
                />
              </label>

              <div className="sa-modal-row">
                <label className="sa-modal-field">
                  <span>City</span>
                  <input
                    className="sa-modal-input"
                    value={formData.city}
                    onChange={updateField("city")}
                    disabled={isCreating}
                    maxLength={255}
                  />
                </label>

                <label className="sa-modal-field">
                  <span>Pincode</span>
                  <input
                    className="sa-modal-input"
                    value={formData.pincode}
                    onChange={updateField("pincode")}
                    disabled={isCreating}
                    maxLength={10}
                    inputMode="numeric"
                  />
                </label>
              </div>

              <label className="sa-modal-field">
                <span>Loyalty Tier</span>
                <select
                  className="sa-modal-input"
                  value={formData.loyaltyTier}
                  onChange={updateField("loyaltyTier")}
                  disabled={isCreating}
                >
                  {LOYALTY_TIERS.map((tier) => (
                    <option key={tier} value={tier}>
                      {tier}
                    </option>
                  ))}
                </select>
              </label>

              <label className="sa-modal-field">
                <span>Notes / Preferences</span>
                <textarea
                  className="sa-modal-input sa-modal-textarea"
                  value={formData.notes}
                  onChange={updateField("notes")}
                  disabled={isCreating}
                  maxLength={2000}
                  rows={3}
                  placeholder="Service preferences, preferred contact time…"
                />
              </label>

              {createError && (
                <p className="sa-modal-error" role="alert">
                  {createError}
                </p>
              )}

              <div className="sa-modal-footer">
                <button
                  type="button"
                  className="sa-modal-cancel"
                  onClick={closeForm}
                  disabled={isCreating}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="sa-modal-submit"
                  disabled={isCreating}
                >
                  {isCreating ? "CREATING…" : "CREATE CUSTOMER"}
                </button>
              </div>

            </form>
          </div>
        </div>
      )}

    </div>
  );
}
