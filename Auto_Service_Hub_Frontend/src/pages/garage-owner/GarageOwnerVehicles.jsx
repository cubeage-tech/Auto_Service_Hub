import React, { useEffect, useMemo, useState } from "react";
import {
  createVehicle,
  getCustomers,
  getVehicle,
  getVehicles,
  getVehicleServiceHistory,
  updateVehicle,
} from "../../services/vehicleService";
import "./GarageOwnerVehicles.css";

const SUPPORTED_FUEL_TYPES = ["PETROL", "DIESEL", "CNG", "ELECTRIC", "HYBRID", "OTHER"];
const VEHICLE_STATUSES = [
  { value: "IN_SERVICE", label: "In Service" },
  { value: "WAITING", label: "Waiting" },
  { value: "DELIVERED", label: "Delivered" },
];

const emptyForm = {
  registrationNo: "",
  make: "",
  model: "",
  variant: "",
  year: "",
  fuelType: "",
  status: "",
  engineNo: "",
  chassisNo: "",
  mileage: "",
  insuranceExpiry: "",
  warrantyExpiry: "",
  customerId: "",
  notes: "",
};

const formatDate = (value) => {
  if (!value) return "—";
  const date = new Date(value.length === 10 ? `${value}T00:00:00` : value);
  return Number.isNaN(date.getTime())
    ? "—"
    : new Intl.DateTimeFormat("en-IN", {
        day: "2-digit",
        month: "short",
        year: "numeric",
      }).format(date);
};

const formatMileage = (value) =>
  value !== null &&
  value !== undefined &&
  value !== "" &&
  Number.isFinite(Number(value))
    ? `${new Intl.NumberFormat("en-IN").format(Number(value))} km`
    : "—";

const getVehicleStatus = (vehicle) => vehicle.status || vehicle.vehicleStatus || "";
const getVehicleFuelType = (vehicle) => vehicle.fuelType || vehicle.fuel || "";

const getInsuranceWarning = (expiry) => {
  if (!expiry) return false;
  const expiryDate = new Date(`${expiry}T00:00:00`);
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const daysRemaining = (expiryDate.getTime() - today.getTime()) / 86400000;
  return !Number.isNaN(daysRemaining) && daysRemaining <= 60;
};

const toFormData = (vehicle) => ({
  registrationNo: vehicle.registrationNo || "",
  make: vehicle.make || "",
  model: vehicle.model || "",
  variant: vehicle.variant || "",
  year: vehicle.year ?? "",
  fuelType: vehicle.fuelType || "",
  status: vehicle.status || "",
  engineNo: vehicle.engineNo || "",
  chassisNo: vehicle.chassisNo || "",
  mileage: vehicle.mileage ?? "",
  insuranceExpiry: vehicle.insuranceExpiry || "",
  warrantyExpiry: vehicle.warrantyExpiry || "",
  customerId: vehicle.customerId ?? "",
  notes: "",
});

function GarageOwnerVehicles() {
  const [vehicles, setVehicles] = useState([]);
  const [lastServiceDates, setLastServiceDates] = useState({});
  const [serviceHistoryLoading, setServiceHistoryLoading] = useState(false);
  const [serviceHistoryFailures, setServiceHistoryFailures] = useState(0);
  const [customers, setCustomers] = useState([]);
  const [customersLoading, setCustomersLoading] = useState(true);
  const [search, setSearch] = useState("");
  const [fuelFilter, setFuelFilter] = useState("");
  const [statusFilter, setStatusFilter] = useState("");
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [customerError, setCustomerError] = useState("");
  const [refreshKey, setRefreshKey] = useState(0);
  const [showForm, setShowForm] = useState(false);
  const [editingVehicleId, setEditingVehicleId] = useState(null);
  const [editingCustomer, setEditingCustomer] = useState(null);
  const [formData, setFormData] = useState(emptyForm);
  const [formError, setFormError] = useState("");
  const [saving, setSaving] = useState(false);
  const [notice, setNotice] = useState("");
  const [selectedVehicle, setSelectedVehicle] = useState(null);
  const [vehicleDetails, setVehicleDetails] = useState(null);
  const [serviceHistory, setServiceHistory] = useState(null);
  const [detailsLoading, setDetailsLoading] = useState(false);
  const [detailsError, setDetailsError] = useState("");
  const [historyError, setHistoryError] = useState("");

  useEffect(() => {
    let active = true;
    setLoading(true);
    setLoadError("");
    getVehicles({ fuelType: fuelFilter, status: statusFilter })
      .then((loadedVehicles) => {
        if (!active) return;
        setVehicles(loadedVehicles);
      })
      .catch((error) => {
        if (active) {
          setLoadError(error instanceof Error ? error.message : "Unable to load vehicles.");
        }
      })
      .finally(() => {
        if (active) setLoading(false);
      });

    return () => {
      active = false;
    };
  }, [refreshKey, fuelFilter, statusFilter]);

  useEffect(() => {
    let active = true;
    setCustomerError("");
    setCustomersLoading(true);
    getCustomers()
      .then((loadedCustomers) => {
        if (active) setCustomers(loadedCustomers);
      })
      .catch((error) => {
        if (active) {
          setCustomerError(error instanceof Error ? error.message : "Unable to load customers.");
        }
      })
      .finally(() => {
        if (active) setCustomersLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    let active = true;
    setLastServiceDates({});
    setServiceHistoryFailures(0);

    if (vehicles.length === 0) {
      setServiceHistoryLoading(false);
      return () => {
        active = false;
      };
    }

    setServiceHistoryLoading(true);
    const loadLastServiceDates = async () => {
      const dates = {};
      let failures = 0;
      const batchSize = 4;

      for (let start = 0; start < vehicles.length; start += batchSize) {
        const batch = vehicles.slice(start, start + batchSize);
        const results = await Promise.allSettled(
          batch.map((vehicle) => getVehicleServiceHistory(vehicle.id))
        );
        if (!active) return;

        results.forEach((result, index) => {
          if (result.status === "rejected") {
            failures += 1;
            return;
          }
          const latestVisit = result.value?.visits?.[0];
          const lastServiceDate = latestVisit?.completedDate || latestVisit?.assignedDate;
          if (lastServiceDate) {
            dates[batch[index].id] = lastServiceDate;
          }
        });
        setLastServiceDates({ ...dates });
        setServiceHistoryFailures(failures);
      }

      if (active) setServiceHistoryLoading(false);
    };

    loadLastServiceDates();
    return () => {
      active = false;
    };
  }, [vehicles]);

  useEffect(() => {
    if (!notice) return undefined;
    const timeoutId = window.setTimeout(() => setNotice(""), 4000);
    return () => window.clearTimeout(timeoutId);
  }, [notice]);

  const fuelTypes = SUPPORTED_FUEL_TYPES;
  const statuses = VEHICLE_STATUSES;

  const filteredVehicles = useMemo(() => {
    const searchValue = search.trim().toLowerCase();
    return vehicles.filter((vehicle) =>
      (!searchValue ||
        [
          vehicle.registrationNo,
          vehicle.customerName,
          vehicle.make,
          vehicle.model,
          vehicle.variant,
          vehicle.year,
        ]
          .filter((value) => value !== null && value !== undefined)
          .some((value) => String(value).toLowerCase().includes(searchValue))) &&
      (!fuelFilter || getVehicleFuelType(vehicle) === fuelFilter) &&
      (!statusFilter || getVehicleStatus(vehicle) === statusFilter)
    );
  }, [vehicles, search, fuelFilter, statusFilter]);

  const mileageValues = vehicles
    .filter((vehicle) => vehicle.mileage !== null && vehicle.mileage !== undefined && vehicle.mileage !== "")
    .map((vehicle) => Number(vehicle.mileage))
    .filter(Number.isFinite);
  const averageMileage = mileageValues.length
    ? formatMileage(Math.round(mileageValues.reduce((sum, value) => sum + value, 0) / mileageValues.length))
    : "—";
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const sixtyDaysFromNow = new Date(today);
  sixtyDaysFromNow.setDate(sixtyDaysFromNow.getDate() + 60);
  const expiringInsuranceCount = vehicles.filter((vehicle) => {
    if (!vehicle.insuranceExpiry) return false;
    const expiry = new Date(`${vehicle.insuranceExpiry}T00:00:00`);
    return expiry >= today && expiry <= sixtyDaysFromNow;
  }).length;
  const inServiceCount = vehicles.filter((vehicle) => getVehicleStatus(vehicle) === "IN_SERVICE").length;
  const hasVehicleStatuses = vehicles.some((vehicle) => getVehicleStatus(vehicle));

  const closeForm = () => {
    setShowForm(false);
    setEditingVehicleId(null);
    setEditingCustomer(null);
    setFormData(emptyForm);
    setFormError("");
  };

  const openCreateForm = () => {
    setFormData(emptyForm);
    setEditingVehicleId(null);
    setEditingCustomer(null);
    setFormError("");
    setShowForm(true);
  };

  const openEditForm = (vehicle) => {
    setFormData(toFormData(vehicle));
    setEditingVehicleId(vehicle.id);
    setEditingCustomer(vehicle);
    setFormError("");
    setSelectedVehicle(null);
    setVehicleDetails(null);
    setServiceHistory(null);
    setShowForm(true);
  };

  const handleInputChange = (event) => {
    const { name, value } = event.target;
    setFormData((previous) => ({ ...previous, [name]: value }));
  };

  const handleSaveVehicle = async (event) => {
    event.preventDefault();
    setFormError("");

    if (!formData.registrationNo.trim()) {
      setFormError("Registration number is required.");
      return;
    }
    const mileage = formData.mileage === "" ? null : Number(formData.mileage);
    const year = formData.year === "" ? null : Number(formData.year);
    if (mileage !== null && (!Number.isInteger(mileage) || mileage < 0)) {
      setFormError("Mileage must be a whole number greater than or equal to zero.");
      return;
    }
    if (year !== null && (!Number.isInteger(year) || year < 1886 || year > new Date().getFullYear() + 1)) {
      setFormError("Enter a valid vehicle year.");
      return;
    }

    const request = {
      registrationNo: formData.registrationNo.trim(),
      make: formData.make.trim() || null,
      model: formData.model.trim() || null,
      variant: formData.variant.trim() || null,
      year,
      engineNo: formData.engineNo.trim() || null,
      chassisNo: formData.chassisNo.trim() || null,
      mileage,
      insuranceExpiry: formData.insuranceExpiry || null,
      warrantyExpiry: formData.warrantyExpiry || null,
      customerId: formData.customerId ? Number(formData.customerId) : null,
      fuelType: formData.fuelType || null,
      status: formData.status || null,
    };

    setSaving(true);
    try {
      if (editingVehicleId) {
        await updateVehicle(editingVehicleId, request);
        setNotice(`Vehicle ${request.registrationNo} updated.`);
      } else {
        await createVehicle(request);
        setNotice(`Vehicle ${request.registrationNo} registered.`);
      }
      closeForm();
      setRefreshKey((previous) => previous + 1);
    } catch (error) {
      setFormError(error.message || "Unable to save this vehicle.");
    } finally {
      setSaving(false);
    }
  };

  const openDetails = async (vehicle) => {
    setSelectedVehicle(vehicle);
    setVehicleDetails(null);
    setServiceHistory(null);
    setDetailsError("");
    setHistoryError("");
    setDetailsLoading(true);

    const [vehicleResult, historyResult] = await Promise.allSettled([
      getVehicle(vehicle.id),
      getVehicleServiceHistory(vehicle.id),
    ]);

    if (vehicleResult.status === "fulfilled") {
      setVehicleDetails(vehicleResult.value);
    } else {
      setDetailsError(vehicleResult.reason?.message || "Unable to load vehicle details.");
    }
    if (historyResult.status === "fulfilled") {
      setServiceHistory(historyResult.value);
    } else {
      setHistoryError(historyResult.reason?.message || "Unable to load service history.");
    }
    setDetailsLoading(false);
  };

  const closeDetails = () => {
    setSelectedVehicle(null);
    setVehicleDetails(null);
    setServiceHistory(null);
    setDetailsError("");
    setHistoryError("");
  };

  return (
    <>
      <div className="garage-vehicles-page">
        <div className="vehicles-heading-row">
          <h1>VEHICLES</h1>
          <button
            type="button"
            className="register-vehicle-button"
            onClick={openCreateForm}
          >
            + REGISTER VEHICLE
          </button>
        </div>

        {notice && (
          <p className="vehicle-success-notice" role="status">
            {notice}
          </p>
        )}

        <section className="vehicle-stats">
          <div className="vehicle-stat-card">
            <div className="vehicle-stat-label">TOTAL VEHICLES</div>
            <div className="vehicle-stat-value">{loading ? "…" : loadError ? "—" : vehicles.length}</div>
          </div>
          <div className="vehicle-stat-card">
            <div className="vehicle-stat-label">IN SERVICE</div>
            <div className="vehicle-stat-value lime">
              {loading ? "…" : loadError || !hasVehicleStatuses ? "—" : inServiceCount}
            </div>
            <span className="vehicle-sr-only">
              Count of vehicles with In Service status.
            </span>
          </div>
          <div className="vehicle-stat-card">
            <div className="vehicle-stat-label">INSURANCE EXPIRING</div>
            <div className="vehicle-stat-value">
              {loading ? "…" : loadError ? "—" : expiringInsuranceCount}
            </div>
            <div className="vehicle-stat-description">within 60 days</div>
          </div>
          <div className="vehicle-stat-card">
            <div className="vehicle-stat-label">AVG MILEAGE</div>
            <div className="vehicle-stat-value">
              {loading ? "…" : loadError ? "—" : averageMileage}
            </div>
          </div>
        </section>

        <div className="vehicle-filter-area">
          <input
            type="search"
            className="vehicle-search"
            placeholder="Search by reg, owner, model..."
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            aria-label="Search vehicles"
          />
          <div className="vehicle-dropdown-filters">
            <select
              className="vehicle-filter"
              value={fuelFilter}
              onChange={(event) => setFuelFilter(event.target.value)}
              aria-label="Filter by fuel type"
            >
              <option value="">All Fuel Types</option>
              {SUPPORTED_FUEL_TYPES.map((fuelType) => (
                <option key={fuelType} value={fuelType}>{fuelType}</option>
              ))}
            </select>
            <select
              className="vehicle-filter"
              value={statusFilter}
              onChange={(event) => setStatusFilter(event.target.value)}
              aria-label="Filter by status"
            >
              <option value="">All Statuses</option>
              {statuses.map((status) => (
                <option key={status.value} value={status.value}>
                  {status.label}
                </option>
              ))}
            </select>
          </div>
        </div>

        <div className="vehicles-table-panel">
          <div className="vehicles-table-wrapper">
            <table className="vehicles-table">
              <thead>
                <tr>
                  <th>REG. NO.</th>
                  <th>MAKE &amp; MODEL</th>
                  <th>YEAR</th>
                  <th>OWNER</th>
                  <th>MILEAGE</th>
                  <th>INSURANCE EXPIRY</th>
                  <th>LAST SERVICE</th>
                  <th>STATUS</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {filteredVehicles.map((vehicle) => {
                  const insuranceWarning = getInsuranceWarning(vehicle.insuranceExpiry);
                  return (
                    <tr key={vehicle.id}>
                      <td className="vehicle-registration" title={vehicle.registrationNo || ""}>{vehicle.registrationNo || "—"}</td>
                      <td className="vehicle-model">
                        <div className="vehicle-model-content">
                          <strong title={[vehicle.model, vehicle.variant].filter(Boolean).join(" ")}>
                            {[vehicle.model, vehicle.variant].filter(Boolean).join(" ") || "—"}
                          </strong>
                          <span title={vehicle.make || ""}>{vehicle.make || "—"}</span>
                        </div>
                      </td>
                      <td className="vehicle-year">{vehicle.year || "—"}</td>
                      <td className="vehicle-owner" title={vehicle.customerName || ""}>
                        {vehicle.customerName || "Not available"}
                      </td>
                      <td className="vehicle-mileage">{formatMileage(vehicle.mileage)}</td>
                      <td className={insuranceWarning ? "vehicle-insurance warning" : "vehicle-insurance"}>
                        {formatDate(vehicle.insuranceExpiry)}
                        {insuranceWarning && <span className="warning-icon" aria-label="Expired or expiring soon">⚠</span>}
                      </td>
                      <td className="vehicle-last-service" title={lastServiceDates[vehicle.id] || ""}>
                        {lastServiceDates[vehicle.id]
                          ? formatDate(lastServiceDates[vehicle.id])
                          : serviceHistoryLoading
                            ? "Loading…"
                            : "—"}
                      </td>
                      <td>
                        <span className="vehicle-status">
                          {getVehicleStatus(vehicle).replaceAll("_", " ") || "Not available"}
                        </span>
                      </td>
                      <td>
                        <button
                          type="button"
                          className="vehicle-view-button"
                          onClick={() => openDetails(vehicle)}
                        >
                          View
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            {serviceHistoryFailures > 0 && (
              <p className="vehicle-data-note" role="status">
                Service history could not be loaded for {serviceHistoryFailures} {serviceHistoryFailures === 1 ? "vehicle" : "vehicles"}.
              </p>
            )}

            {loading && <div className="no-vehicles" role="status">Loading vehicles…</div>}
            {!loading && loadError && (
              <div className="no-vehicles" role="alert">
                <p>{loadError}</p>
                <button type="button" className="vehicle-view-button" onClick={() => setRefreshKey((value) => value + 1)}>
                  Retry
                </button>
              </div>
            )}
            {!loading && !loadError && filteredVehicles.length === 0 && (
              <div className="no-vehicles">
                {vehicles.length ? "No vehicles match your search." : "No vehicles registered yet."}
              </div>
            )}
          </div>
        </div>
      </div>

      {showForm && (
        <div
          className="vehicle-modal-overlay"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget && !saving) closeForm();
          }}
        >
          <div className="vehicle-modal vehicle-registration-modal" role="dialog" aria-modal="true" aria-labelledby="vehicle-form-title">
            <div className="vehicle-modal-header">
              <h2 id="vehicle-form-title">{editingVehicleId ? "EDIT VEHICLE" : "REGISTER VEHICLE"}</h2>
              <button type="button" className="vehicle-modal-close" onClick={closeForm} aria-label="Close" disabled={saving}>
                ×
              </button>
            </div>

            <form className="vehicle-modal-form" onSubmit={handleSaveVehicle}>
              <div className="vehicle-modal-form-scroll">
                {formError && <p role="alert" className="vehicle-form-error">{formError}</p>}
                {customerError && (
                  <p role="alert" className="vehicle-form-error">
                    Customer choices could not be loaded: {customerError}
                  </p>
                )}

                <div className="vehicle-form-group full">
                  <label htmlFor="registrationNo">REGISTRATION NUMBER *</label>
                  <input
                    id="registrationNo"
                    name="registrationNo"
                    type="text"
                    placeholder="MH-XX-YY-0000"
                    value={formData.registrationNo}
                    onChange={handleInputChange}
                    required
                  />
                </div>

                <div className="vehicle-form-grid">
                  <div className="vehicle-form-group">
                    <label htmlFor="make">MAKE / BRAND</label>
                    <input id="make" name="make" value={formData.make} onChange={handleInputChange} />
                  </div>
                  <div className="vehicle-form-group">
                    <label htmlFor="model">MODEL</label>
                    <input id="model" name="model" value={formData.model} onChange={handleInputChange} />
                  </div>
                </div>

                <div className="vehicle-form-grid">
                  <div className="vehicle-form-group">
                    <label htmlFor="year">YEAR</label>
                    <input id="year" name="year" type="number" min="1886" max={new Date().getFullYear() + 1} value={formData.year} onChange={handleInputChange} />
                  </div>
                  <div className="vehicle-form-group">
                    <label htmlFor="fuelType">FUEL TYPE</label>
                    <select id="fuelType" name="fuelType" value={formData.fuelType} onChange={handleInputChange}>
                      <option value="">Unknown / Not provided</option>
                      {fuelTypes.map((fuelType) => (
                        <option key={fuelType} value={fuelType}>{fuelType}</option>
                      ))}
                    </select>
                  </div>
                </div>

                <div className="vehicle-form-group full">
                  <label htmlFor="vehicleStatus">STATUS</label>
                  <select
                    id="vehicleStatus"
                    name="status"
                    value={formData.status}
                    onChange={handleInputChange}
                  >
                    <option value="">Unknown / Not provided</option>
                    {VEHICLE_STATUSES.map((status) => (
                      <option key={status.value} value={status.value}>{status.label}</option>
                    ))}
                  </select>
                </div>

                <div className="vehicle-form-group full">
                  <label htmlFor="customerId">CUSTOMER / OWNER</label>
                  <select
                    id="customerId"
                    name="customerId"
                    value={formData.customerId}
                    onChange={handleInputChange}
                    disabled={customersLoading}
                  >
                    <option value="">
                      {customersLoading
                        ? "Loading customers..."
                        : editingVehicleId
                          ? "Keep current customer"
                          : "No customer selected"}
                    </option>
                    {formData.customerId &&
                      editingCustomer?.customerId === Number(formData.customerId) &&
                      !customers.some((customer) => customer.id === editingCustomer.customerId) && (
                        <option value={formData.customerId}>{editingCustomer.customerName || "Current customer"}</option>
                      )}
                    {customers.map((customer) => (
                      <option key={customer.id} value={customer.id}>{customer.name}</option>
                    ))}
                  </select>
                </div>

                <div className="vehicle-form-grid">
                  <div className="vehicle-form-group">
                    <label htmlFor="mileage">CURRENT MILEAGE (KM)</label>
                    <input id="mileage" name="mileage" type="number" min="0" step="1" value={formData.mileage} onChange={handleInputChange} />
                  </div>
                  <div className="vehicle-form-group vehicle-engine-chassis">
                    <label>ENGINE / CHASSIS NUMBER</label>
                    <div className="vehicle-engine-chassis-inputs">
                      <input id="engineNo" name="engineNo" aria-label="Engine number" placeholder="Engine number" value={formData.engineNo} onChange={handleInputChange} />
                      <input id="chassisNo" name="chassisNo" aria-label="Chassis number" placeholder="Chassis number" value={formData.chassisNo} onChange={handleInputChange} />
                    </div>
                  </div>
                </div>

                <div className="vehicle-form-group full">
                  <label htmlFor="insuranceExpiry">INSURANCE EXPIRY</label>
                  <input id="insuranceExpiry" name="insuranceExpiry" type="date" value={formData.insuranceExpiry} onChange={handleInputChange} />
                </div>

                <div className="vehicle-form-group full">
                  <label htmlFor="warrantyExpiry">WARRANTY EXPIRY</label>
                  <input id="warrantyExpiry" name="warrantyExpiry" type="date" value={formData.warrantyExpiry} onChange={handleInputChange} />
                </div>

                <div className="vehicle-form-group full">
                  <label htmlFor="vehicleNotes">NOTES</label>
                  <textarea
                    id="vehicleNotes"
                    name="notes"
                    placeholder="Additional vehicle information..."
                    value={formData.notes}
                    onChange={handleInputChange}
                    rows="2"
                  />
                </div>

                <p className="vehicle-form-api-note">
                  Notes are not supported by the vehicle API and will not be saved.
                </p>
              </div>

              <div className="vehicle-modal-footer">
                <button type="submit" className="vehicle-register-submit" disabled={saving}>
                  {saving ? "SAVING…" : editingVehicleId ? "SAVE CHANGES" : "REGISTER VEHICLE"}
                </button>
                <button type="button" className="vehicle-register-cancel" onClick={closeForm} disabled={saving}>
                  Cancel
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {selectedVehicle && (
        <div
          className="vehicle-modal-overlay"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) closeDetails();
          }}
        >
          <div className="vehicle-modal" role="dialog" aria-modal="true" aria-labelledby="vehicle-details-title">
            <div className="vehicle-modal-header">
              <h2 id="vehicle-details-title">VEHICLE DETAILS &amp; SERVICE HISTORY</h2>
              <button type="button" className="vehicle-modal-close" onClick={closeDetails} aria-label="Close">×</button>
            </div>
            <div className="vehicle-modal-form">
              {detailsLoading && <p role="status">Loading vehicle details and service history…</p>}
              {detailsError && <p role="alert" style={{ color: "#ffa900" }}>{detailsError}</p>}
              {vehicleDetails && (
                <>
                  <div className="vehicle-form-grid">
                    <div className="vehicle-form-group"><label>REGISTRATION</label><strong>{vehicleDetails.registrationNo || "—"}</strong></div>
                    <div className="vehicle-form-group"><label>MAKE / MODEL</label><strong>{[vehicleDetails.make, vehicleDetails.model, vehicleDetails.variant].filter(Boolean).join(" ") || "—"}</strong></div>
                  </div>
                  <div className="vehicle-form-grid">
                    <div className="vehicle-form-group"><label>YEAR</label><strong>{vehicleDetails.year || "—"}</strong></div>
                    <div className="vehicle-form-group"><label>OWNER</label><strong>{vehicleDetails.customerName || "—"}</strong></div>
                  </div>
                  <div className="vehicle-form-grid">
                    <div className="vehicle-form-group"><label>MILEAGE</label><strong>{formatMileage(vehicleDetails.mileage)}</strong></div>
                    <div className="vehicle-form-group"><label>INSURANCE EXPIRY</label><strong>{formatDate(vehicleDetails.insuranceExpiry)}</strong></div>
                  </div>
                  <div className="vehicle-form-grid">
                    <div className="vehicle-form-group"><label>WARRANTY EXPIRY</label><strong>{formatDate(vehicleDetails.warrantyExpiry)}</strong></div>
                    <div className="vehicle-form-group"><label>CREATED</label><strong>{formatDate(vehicleDetails.createdAt)}</strong></div>
                  </div>
                  <div className="vehicle-form-grid">
                    <div className="vehicle-form-group"><label>ENGINE NUMBER</label><strong>{vehicleDetails.engineNo || "—"}</strong></div>
                    <div className="vehicle-form-group"><label>CHASSIS NUMBER</label><strong>{vehicleDetails.chassisNo || "—"}</strong></div>
                  </div>
                  <div className="vehicle-form-group full">
                    <label>SERVICE HISTORY {serviceHistory ? `(${serviceHistory.totalVisits ?? serviceHistory.visits?.length ?? 0} visits)` : ""}</label>
                    {historyError && <p role="alert" style={{ color: "#ffa900" }}>{historyError}</p>}
                    {!historyError && serviceHistory?.visits?.length === 0 && <p>No service history is recorded for this vehicle.</p>}
                    {serviceHistory?.visits?.map((visit) => (
                      <article key={visit.jobCardId} style={{ padding: "12px 0", borderBottom: "1px solid #292b2c" }}>
                        <strong>{visit.jobCardNumber || `Job #${visit.jobCardId}`} · {visit.status || "Status unavailable"}</strong>
                        <p>{visit.serviceType || "Service"} · {formatDate(visit.completedDate || visit.assignedDate)}</p>
                        {visit.tasks?.length > 0 && (
                          <ul>
                            {visit.tasks.map((task) => <li key={task.id}>{task.description} — {task.status}</li>)}
                          </ul>
                        )}
                        <p>{visit.invoiceTotal == null ? "Not invoiced" : `Invoiced: ${visit.invoiceTotal}`}</p>
                      </article>
                    ))}
                  </div>
                  <div className="vehicle-modal-footer">
                    <button type="button" className="vehicle-register-submit" onClick={() => openEditForm(vehicleDetails)}>
                      EDIT VEHICLE
                    </button>
                    <button type="button" className="vehicle-register-cancel" onClick={closeDetails}>
                      Close
                    </button>
                  </div>
                </>
              )}
            </div>
          </div>
        </div>
      )}
    </>
  );
}

export default GarageOwnerVehicles;
