import React, { useCallback, useEffect, useMemo, useState } from "react";
import { createCustomer, getCustomers } from "../../services/customerService";
import "./GarageOwnerCustomers.css";

function GarageOwnerCustomers() {
  const [customers, setCustomers] = useState([]);
  const [search, setSearch] = useState("");
  const [showNewCustomer, setShowNewCustomer] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const [isCreating, setIsCreating] = useState(false);
  const [loadError, setLoadError] = useState("");
  const [createError, setCreateError] = useState("");
  const [successMessage, setSuccessMessage] = useState("");

  const [formData, setFormData] = useState({
    firstName: "",
    lastName: "",
    phone: "",
    email: "",
    address: "",
    city: "",
    pincode: "",
    loyaltyTier: "Bronze",
    notes: "",
  });

  const loadCustomers = useCallback(async ({
    showLoading = true,
    savedCustomer = null,
  } = {}) => {
    if (showLoading) {
      setIsLoading(true);
    }
    setLoadError("");

    try {
      const fetchedCustomers = await getCustomers();
      const refreshed = [...fetchedCustomers];
      if (
        savedCustomer?.id != null &&
        !refreshed.some((customer) => String(customer.id) === String(savedCustomer.id))
      ) {
        refreshed.unshift(savedCustomer);
      }
      setCustomers([
        ...new Map(refreshed.map((customer) => [String(customer.id), customer])).values(),
      ]);
      return true;
    } catch (error) {
      setLoadError(error instanceof Error ? error.message : "Unable to load customers.");
      if (savedCustomer?.id != null) {
        setCustomers((current) => [
          savedCustomer,
          ...current.filter(
            (customer) => String(customer.id) !== String(savedCustomer.id)
          ),
        ]);
      }
      return false;
    } finally {
      if (showLoading) {
        setIsLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    void loadCustomers();
  }, [loadCustomers]);

  const filteredCustomers = useMemo(() => {
    const value = search.trim().toLowerCase();

    if (!value) {
      return customers;
    }

    return customers.filter((customer) => {
      return (
        String(customer.id ?? "").toLowerCase().includes(value) ||
        String(customer.name ?? "").toLowerCase().includes(value) ||
        String(customer.phone ?? "").toLowerCase().includes(value) ||
        String(customer.email ?? "").toLowerCase().includes(value)
      );
    });
  }, [customers, search]);

  /* =========================================================
     FORM HANDLER
  ========================================================= */

  const handleChange = (e) => {
    const { name, value } = e.target;

    setCreateError("");
    setFormData((previous) => ({
      ...previous,
      [name]: value,
    }));
  };

  /* =========================================================
     CREATE CUSTOMER
  ========================================================= */

  const handleCreateCustomer = async (e) => {
    e.preventDefault();

    if (isCreating) {
      return;
    }

    if (
      !formData.firstName.trim() ||
      !formData.lastName.trim() ||
      !formData.phone.trim() ||
      !formData.email.trim()
    ) {
      setCreateError("Please fill in all required fields.");
      return;
    }

    setIsCreating(true);
    setCreateError("");
    setSuccessMessage("");

    const address = [formData.address, formData.city, formData.pincode]
      .map((part) => part.trim())
      .filter(Boolean)
      .join(", ");

    try {
      const savedCustomer = await createCustomer({
        name: `${formData.firstName.trim()} ${formData.lastName.trim()}`,
        phone: formData.phone.trim(),
        email: formData.email.trim(),
        address,
        status: "ACTIVE",
        loyaltyTier: formData.loyaltyTier,
        notes: formData.notes.trim(),
      });

      setSearch("");
      setSuccessMessage(`${savedCustomer.name || "Customer"} created successfully.`);
      setShowNewCustomer(false);
      await loadCustomers({ showLoading: false, savedCustomer });

      setFormData({
        firstName: "",
        lastName: "",
        phone: "",
        email: "",
        address: "",
        city: "",
        pincode: "",
        loyaltyTier: "Bronze",
        notes: "",
      });

    } catch (error) {
      setCreateError(error instanceof Error ? error.message : "Unable to create customer.");
    } finally {
      setIsCreating(false);
    }
  };

  /* =========================================================
     CLOSE MODAL
  ========================================================= */

  const closeModal = () => {
    if (!isCreating) {
      setShowNewCustomer(false);
    }
  };

  return (
    <>
      <div className="garage-customers-page">

        {/* =====================================================
            PAGE TITLE + BUTTON
        ===================================================== */}

        <div className="garage-customers-heading-row">

          <h1 className="garage-customers-title">
            CUSTOMERS
          </h1>

          <button
            type="button"
            className="garage-customers-new-button"
            onClick={() => {
              setCreateError("");
              setShowNewCustomer(true);
            }}
          >
            + NEW CUSTOMER
          </button>

        </div>

        {successMessage && (
          <div className="customers-feedback customers-feedback-success" role="status">
            {successMessage}
          </div>
        )}

        {loadError && (
          <div className="customers-feedback customers-feedback-error" role="alert">
            <span>{loadError}</span>
            <button type="button" onClick={() => void loadCustomers()}>
              Retry
            </button>
          </div>
        )}


        {/* =====================================================
            SEARCH
        ===================================================== */}

        <div className="garage-customers-search-row">

          <div className="garage-customers-search-box">

            <span className="garage-customers-search-icon">
              ⌕
            </span>

            <input
              type="text"
              placeholder="Search by name or phone..."
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />

          </div>

        </div>


        {/* =====================================================
            CUSTOMER TABLE
        ===================================================== */}

        <div className="garage-customers-table-container">

          <table className="garage-customers-table">

            <thead>
              <tr>
                <th>ID</th>
                <th>NAME</th>
                <th>PHONE</th>
                <th>EMAIL</th>
                <th>VEHICLES</th>
                <th>VISITS</th>
                <th>LAST VISIT</th>
                <th>LOYALTY</th>
                <th>BALANCE</th>
              </tr>
            </thead>

            <tbody>
              {isLoading && customers.length === 0 ? (
                <tr>
                  <td className="customer-table-state" colSpan={9}>Loading customers…</td>
                </tr>
              ) : filteredCustomers.length > 0 ? (
                filteredCustomers.map((customer) => (
                <tr key={customer.id ?? `${customer.phone}-${customer.name}`}>

                  <td>
                    <span className="customer-id">
                      {customer.id ?? "—"}
                    </span>
                  </td>

                  <td>
                    <span className="customer-name-cell">
                      <span className="customer-name">{customer.name || "—"}</span>
                      {customer.notes && (
                        <span className="customer-notes" title={customer.notes}>
                          {customer.notes}
                        </span>
                      )}
                    </span>
                  </td>

                  <td>
                    <span className="customer-phone">
                      {customer.phone || "—"}
                    </span>
                  </td>

                  <td>
                    <span className="customer-email">
                      {customer.email || "—"}
                    </span>
                  </td>

                  <td className="customer-number">
                    —
                  </td>

                  <td className="customer-number">
                    —
                  </td>

                  <td>
                    <span className="customer-date">
                      —
                    </span>
                  </td>

                  <td>
                    <span className="customer-loyalty">
                      {customer.loyaltyTier || "—"}
                    </span>
                  </td>

                  <td>
                    <span className="customer-balance">—</span>
                  </td>

                </tr>
                ))
              ) : (
                <tr>
                  <td className="customer-table-state" colSpan={9}>
                    {loadError
                      ? "Customers could not be loaded."
                      : search
                        ? "No customers match your search."
                        : "No customers found."}
                  </td>
                </tr>
              )}
            </tbody>

          </table>

        </div>

      </div>


      {/* =========================================================
          NEW CUSTOMER MODAL
      ========================================================= */}

      {showNewCustomer && (
        <div
          className="garage-customer-modal-overlay"
          onMouseDown={(e) => {
            if (e.target === e.currentTarget && !isCreating) {
              closeModal();
            }
          }}
        >

          <div className="garage-customer-modal">

            {/* MODAL HEADER */}

            <div className="garage-customer-modal-header">

              <h2>
                NEW CUSTOMER
              </h2>

              <button
                type="button"
                className="garage-customer-modal-close"
                onClick={closeModal}
                aria-label="Close"
              >
                ×
              </button>

            </div>


            {/* MODAL BODY */}

            <form
              className="garage-customer-modal-form"
              onSubmit={handleCreateCustomer}
            >

              <div className="garage-customer-form-scroll">

              {/* FIRST + LAST NAME */}

              <div className="garage-customer-form-row">

                <div className="garage-customer-form-group">

                  <label>
                    FIRST NAME
                  </label>

                  <input
                    type="text"
                    name="firstName"
                    placeholder="Rajesh"
                    value={formData.firstName}
                    onChange={handleChange}
                    required
                  />

                </div>


                <div className="garage-customer-form-group">

                  <label>
                    LAST NAME
                  </label>

                  <input
                    type="text"
                    name="lastName"
                    placeholder="Kumar"
                    value={formData.lastName}
                    onChange={handleChange}
                    required
                  />

                </div>

              </div>


              {/* PHONE */}

              <div className="garage-customer-form-group">

                <label>
                  PHONE NUMBER
                </label>

                <input
                  type="tel"
                  name="phone"
                  placeholder="+91 98765 43210"
                  value={formData.phone}
                  onChange={handleChange}
                  required
                />

              </div>


              {/* EMAIL */}

              <div className="garage-customer-form-group">

                <label>
                  EMAIL ADDRESS
                </label>

                <input
                  type="email"
                  name="email"
                  placeholder="rajesh.kumar@email.com"
                  value={formData.email}
                  onChange={handleChange}
                  required
                />

              </div>


              {/* ADDRESS */}

              <div className="garage-customer-form-group">

                <label>
                  ADDRESS
                </label>

                <textarea
                  name="address"
                  placeholder="123, MG Road, Mumbai - 400001"
                  value={formData.address}
                  onChange={handleChange}
                  rows="2"
                />

              </div>


              {/* CITY + PINCODE */}

              <div className="garage-customer-form-row">

                <div className="garage-customer-form-group">

                  <label>
                    CITY
                  </label>

                  <input
                    type="text"
                    name="city"
                    placeholder="Mumbai"
                    value={formData.city}
                    onChange={handleChange}
                  />

                </div>


                <div className="garage-customer-form-group">

                  <label>
                    PINCODE
                  </label>

                  <input
                    type="text"
                    name="pincode"
                    placeholder="400001"
                    value={formData.pincode}
                    onChange={handleChange}
                  />

                </div>

              </div>

              <div className="garage-customer-form-group">

                <label htmlFor="customer-loyalty-tier">
                  LOYALTY TIER
                </label>

                <select
                  id="customer-loyalty-tier"
                  name="loyaltyTier"
                  value={formData.loyaltyTier}
                  onChange={handleChange}
                >
                  <option value="Bronze">Bronze</option>
                  <option value="Silver">Silver</option>
                  <option value="Gold">Gold</option>
                  <option value="Platinum">Platinum</option>
                </select>

              </div>

              <div className="garage-customer-form-group">

                <label htmlFor="customer-notes">
                  NOTES / PREFERENCES
                </label>

                <textarea
                  id="customer-notes"
                  name="notes"
                  placeholder="VIP customer, prefers weekend appointments..."
                  value={formData.notes}
                  onChange={handleChange}
                  maxLength={2000}
                  rows="2"
                />

              </div>

              {createError && (
                <div className="customers-feedback customers-feedback-error" role="alert">
                  {createError}
                </div>
              )}

              </div>

              {/* DIVIDER */}

              <div className="garage-customer-modal-divider"></div>


              {/* BUTTONS */}

              <div className="garage-customer-modal-actions">

                <button
                  type="submit"
                  className="garage-customer-create-button"
                  disabled={isCreating}
                >
                  {isCreating ? "CREATING..." : "CREATE CUSTOMER"}
                </button>

                <button
                  type="button"
                  className="garage-customer-cancel-button"
                  onClick={closeModal}
                  disabled={isCreating}
                >
                  Cancel
                </button>

              </div>

            </form>

          </div>

        </div>
      )}
    </>
  );
}

export default GarageOwnerCustomers;