import React, { useEffect, useMemo, useState } from "react";

import {
  applyServicePackage,
  createServicePackage,
  getPackageApplications,
  getServicePackages,
  setServicePackageActive,
  updateServicePackage,
} from "../../services/packageService";
import { getCustomers } from "../../services/customerService";
import { getCustomerVehicles } from "../../services/vehicleService";

import "./ServiceAdvisor.css";


/* =========================================================
   API CONTRACT
   ServicePackageRequestDTO is exactly the payload the form
   submits — nothing here invents a field:

     name        @NotBlank   package code shown on the card
     type        @NotBlank   the card's code, e.g. "PREM-PLUS"
     price       @NotNull
     duration    optional    free text, e.g. "4–5 hrs" / "12 months"
     gstRate     0–100
     discountPercentage  0–100, display only
     color       one of ACCENT_COLORS, else 409
     popular     optional
     active      optional; create defaults true, update preserves
                 the stored value when omitted
     items[]     { itemName @NotBlank, itemType SERVICE|PART }
                 full replacement; null leaves them alone,
                 an empty list clears them

   ServicePackageResponseDTO adds items[], createdAt, updatedAt.
   ========================================================= */

/* The accent tokens ServicePackageServiceImpl accepts. A package
   stored before `color` existed has no value, and anything outside
   the set would render as an unstyled card, so both fall back. */
const ACCENT_COLORS = ["lime", "blue", "purple", "orange", "green", "red"];
const DEFAULT_ACCENT = "lime";

/* The GST slabs offered in the form. Any 0–100 value the backend
   accepts can be typed; these are the common ones. */
const GST_RATES = ["0", "5", "12", "18", "28"];

/* PackageItemType. Both constants are required — the DTO rejects a
   null itemType with a 400. */
const ITEM_TYPES = [
  { value: "SERVICE", label: "Service" },
  { value: "PART", label: "Part" },
];

const EMPTY_ITEM = { itemName: "", itemType: "SERVICE" };

const EMPTY_FORM = {
  name: "",
  type: "",
  price: "",
  duration: "",
  gstRate: "18",
  discountPercentage: "0",
  color: DEFAULT_ACCENT,
  popular: false,
  active: true,
  items: [EMPTY_ITEM],
};

const NOT_PROVIDED = "—";

/* "12:4" — a stable key for "this package is already on this vehicle".
   The backend enforces the same rule, so this is a hint, not a lock. */
const applicationKey = (packageId, vehicleId) => `${packageId}:${vehicleId}`;

const customerLabel = (customer) =>
  customer.phone ? `${customer.name} · ${customer.phone}` : customer.name;

const vehicleLabel = (vehicle) => {
  const description = [vehicle.make, vehicle.model].filter(Boolean).join(" ");
  return description ? `${vehicle.registrationNo} · ${description}` : vehicle.registrationNo;
};


/* =========================================================
   DASHBOARD-SAFE PLACEMAKERS

   AMC customer counts and monthly package revenue have no
   backend endpoint and no persisted source — the package API
   returns no purchase, subscription or sale record — so those
   two tiles report "not tracked" instead of showing invented
   figures. Only the active count and the average price are
   derived from real rows.
   ========================================================= */

const formatPrice = (value) =>
  Number(value ?? 0).toLocaleString("en-IN");

const toNumber = (value) => {
  if (value === null || value === undefined || value === "") {
    return null;
  }
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
};


/**
 * Map a ServicePackageResponseDTO onto the shape the card renders.
 *
 * An item whose stored type the backend could not read arrives with
 * a null `itemType`; it belongs to neither list and is not rendered.
 *
 * `raw` is the untouched response DTO. setServicePackageActive()
 * re-sends a whole record rather than toggling one flag, so it needs
 * every field the server already holds — not just what the card shows.
 */
function toPackageView(servicePackage) {
  const items = Array.isArray(servicePackage.items) ? servicePackage.items : [];
  const color = String(servicePackage.color ?? "").toLowerCase();

  return {
    id: servicePackage.id,
    name: servicePackage.name ?? "",
    type: servicePackage.type ?? "",
    price: Number(servicePackage.price ?? 0),
    duration: servicePackage.duration ?? "",
    gstRate: toNumber(servicePackage.gstRate),
    discountPercentage: toNumber(servicePackage.discountPercentage),
    popular: servicePackage.popular === true,
    active: servicePackage.active !== false,
    color: ACCENT_COLORS.includes(color) ? color : DEFAULT_ACCENT,
    services: items
      .filter((item) => item.itemType === "SERVICE")
      .map((item) => item.itemName),
    includes: items
      .filter((item) => item.itemType === "PART")
      .map((item) => item.itemName),
    raw: servicePackage,
  };
}


/**
 * Drop items the backend would reject.
 *
 * itemType is @NotNull, so a row whose stored type the backend could
 * not read (it maps an unrecognised value to null rather than failing
 * the read) cannot be echoed back on a write without a 400. Those rows
 * are legacy/unknown and are not carried through a resave.
 */
function toWritableItems(items) {
  return (Array.isArray(items) ? items : [])
    .filter(
      (item) =>
        item &&
        typeof item.itemName === "string" &&
        item.itemName.trim() !== "" &&
        (item.itemType === "SERVICE" || item.itemType === "PART")
    )
    .map((item) => ({
      itemName: item.itemName.trim(),
      itemType: item.itemType,
    }));
}


export default function Packages() {
  const [list, setList] = useState({ status: "loading", data: [], error: "" });
  const [refreshKey, setRefreshKey] = useState(0);

  const [successMessage, setSuccessMessage] = useState("");
  const [actionError, setActionError] = useState("");

  /* Id of the package whose activation toggle is in flight, so only
     that card shows SAVING… and stays disabled. */
  const [savingId, setSavingId] = useState(null);

  /* Modal state. editingId === null means "create". */
  const [showModal, setShowModal] = useState(false);
  const [editingId, setEditingId] = useState(null);
  const [form, setForm] = useState(EMPTY_FORM);
  const [formError, setFormError] = useState("");
  const [isSaving, setIsSaving] = useState(false);

  /* ── APPLY PACKAGE ──
     Every application on record. Loaded with the packages so a card can say
     how many vehicles already hold it, and refreshed after every successful
     application so the dialog opens against current data. */
  const [applications, setApplications] = useState([]);
  const [applicationsError, setApplicationsError] = useState("");

  /* The package currently being applied, or null when the dialog is closed. */
  const [applyTarget, setApplyTarget] = useState(null);
  const [applyForm, setApplyForm] = useState({ customerId: "", vehicleId: "" });
  const [applyError, setApplyError] = useState("");
  const [isApplying, setIsApplying] = useState(false);

  /* Dialog context: customers to pick from, and the vehicles belonging to the
     customer currently chosen. Vehicles are reloaded from the server whenever
     the customer changes rather than filtered out of one full load. */
  const [applyContext, setApplyContext] = useState({
    customersStatus: "idle",
    customers: [],
    customersError: "",
    vehiclesStatus: "idle",
    vehicles: [],
    vehiclesError: "",
  });
  const [customerQuery, setCustomerQuery] = useState("");

  /* Initial load, every retry and every post-write refresh re-read the
     list from the backend. State is only written after the await
     resolves, so React does not treat this as a synchronous
     setState-in-effect. A refresh keeps the current rows on screen
     until the new ones arrive rather than flashing a spinner.

     Packages and applications are fetched together but settle separately: an
     unreadable applications list must not blank out a working packages page,
     and the backend re-checks every rule on write regardless of what arrived
     here. */
  useEffect(() => {
    let cancelled = false;

    const load = async () => {
      const [packagesResult, applicationsResult] = await Promise.allSettled([
        getServicePackages(),
        getPackageApplications(),
      ]);

      if (cancelled) {
        return;
      }

      if (packagesResult.status === "fulfilled") {
        setList({
          status: "success",
          data: (Array.isArray(packagesResult.value) ? packagesResult.value : []).map(toPackageView),
          error: "",
        });
      } else {
        const reason = packagesResult.reason;
        setList({
          status: "error",
          data: [],
          error:
            reason instanceof Error
              ? reason.message
              : "Unable to load service packages.",
        });
      }

      if (applicationsResult.status === "fulfilled") {
        setApplications(
          Array.isArray(applicationsResult.value) ? applicationsResult.value : []
        );
        setApplicationsError("");
      } else {
        setApplications([]);
        const reason = applicationsResult.reason;
        setApplicationsError(
          reason instanceof Error
            ? reason.message
            : "Unable to load package applications."
        );
      }
    };

    load();

    return () => {
      cancelled = true;
    };
  }, [refreshKey]);

  const refreshList = () => setRefreshKey((value) => value + 1);

  const handleRetry = () => {
    setList({ status: "loading", data: [], error: "" });
    setActionError("");
    setSuccessMessage("");
    refreshList();
  };


  /* =========================================================
     STATISTICS — real rows only
     ========================================================= */

  const activeCount = useMemo(
    () => list.data.filter((pkg) => pkg.active).length,
    [list.data]
  );

  /* Averaged across every package the advisor can see, matching
     the garage-owner packages page. Null while the list is still
     loading or empty, so the tile renders a dash rather than ₹0. */
  const averagePrice = useMemo(() => {
    if (list.data.length === 0) {
      return null;
    }
    const total = list.data.reduce((sum, pkg) => sum + pkg.price, 0);
    return total / list.data.length;
  }, [list.data]);

  /* How many vehicles hold each package, from real application rows. */
  const appliedCounts = useMemo(() => {
    const counts = new Map();
    for (const application of applications) {
      counts.set(
        application.servicePackageId,
        (counts.get(application.servicePackageId) ?? 0) + 1
      );
    }
    return counts;
  }, [applications]);

  /* Pairs already on record, so the dialog can say so before the backend
     has to reject the write. */
  const appliedKeys = useMemo(
    () =>
      new Set(
        applications.map((application) =>
          applicationKey(application.servicePackageId, application.vehicleId)
        )
      ),
    [applications]
  );

  const filteredCustomers = useMemo(() => {
    const needle = customerQuery.trim().toLowerCase();
    if (!needle) {
      return applyContext.customers;
    }
    return applyContext.customers.filter((customer) =>
      [customer.name, customer.phone]
        .map((value) => (value == null ? "" : String(value)))
        .join(" ")
        .toLowerCase()
        .includes(needle)
    );
  }, [applyContext.customers, customerQuery]);

  /* Whether the current package/vehicle pair already exists on record. */
  const wouldDuplicate =
    applyTarget !== null &&
    applyForm.vehicleId !== "" &&
    appliedKeys.has(applicationKey(applyTarget.id, applyForm.vehicleId));


  /* =========================================================
     MODAL
     ========================================================= */

  const clearFeedback = () => {
    setSuccessMessage("");
    setActionError("");
  };

  const openCreateModal = () => {
    clearFeedback();
    setEditingId(null);
    setForm(EMPTY_FORM);
    setFormError("");
    setShowModal(true);
  };

  /** Prefill the same modal with the package being edited. */
  const openEditModal = (pkg) => {
    clearFeedback();
    setEditingId(pkg.id);
    setForm({
      name: pkg.name,
      type: pkg.type,
      price: String(pkg.price ?? ""),
      duration: pkg.duration,
      gstRate: pkg.gstRate == null ? "" : String(pkg.gstRate),
      discountPercentage:
        pkg.discountPercentage == null ? "" : String(pkg.discountPercentage),
      color: pkg.color,
      popular: pkg.popular,
      active: pkg.active,
      /* One row per real item, so nothing is dropped unless the user
         deletes it. A package with no items still gets one blank row. */
      items: pkg.services.length + pkg.includes.length > 0
        ? [
            ...pkg.services.map((itemName) => ({ itemName, itemType: "SERVICE" })),
            ...pkg.includes.map((itemName) => ({ itemName, itemType: "PART" })),
          ]
        : [EMPTY_ITEM],
    });
    setFormError("");
    setShowModal(true);
  };

  const closeModal = () => {
    /* An in-flight save must not be dismissed out from under itself. */
    if (isSaving) {
      return;
    }
    setFormError("");
    setShowModal(false);
  };


  /* =========================================================
     FORM
     ========================================================= */

  const updateField = (field) => (event) => {
    const target = event.target;
    const value =
      target.type === "checkbox" ? target.checked : target.value;

    setFormError("");
    setForm((current) => ({ ...current, [field]: value }));
  };

  const updateItem = (index, field, value) => {
    setFormError("");
    setForm((current) => ({
      ...current,
      items: current.items.map((item, i) =>
        i === index ? { ...item, [field]: value } : item
      ),
    }));
  };

  const addItem = () => {
    setForm((current) => ({ ...current, items: [...current.items, EMPTY_ITEM] }));
  };

  const removeItem = (index) => {
    setForm((current) => ({
      ...current,
      items: current.items.filter((_, i) => i !== index),
    }));
  };

  /* Mirrors the DTO constraints so an avoidable round trip is not made.
     The backend remains the authority — its message is what the user
     sees if anything still fails. */
  const validate = () => {
    if (!form.name.trim()) return "Package name is required.";
    if (!form.type.trim()) return "Package code is required.";

    const price = toNumber(form.price);
    if (price === null) return "Enter a valid price.";
    if (price < 0) return "Price cannot be negative.";

    if (form.gstRate !== "") {
      const gst = toNumber(form.gstRate);
      if (gst === null || gst < 0 || gst > 100) {
        return "GST rate must be between 0 and 100.";
      }
    }

    if (form.discountPercentage !== "") {
      const discount = toNumber(form.discountPercentage);
      if (discount === null || discount < 0 || discount > 100) {
        return "Discount must be between 0 and 100.";
      }
    }

    if (
      form.items.some((item) => item.itemName.trim().length > 255)
    ) {
      return "Each item name must be 255 characters or fewer.";
    }

    return "";
  };

  /* Blank item rows are dropped rather than sent: itemName is
     @NotBlank, and an empty list legitimately clears a package's
     contents on update. */
  const buildPayload = () => ({
    name: form.name.trim(),
    type: form.type.trim(),
    price: toNumber(form.price),
    duration: form.duration.trim() || null,
    gstRate: toNumber(form.gstRate),
    discountPercentage: toNumber(form.discountPercentage),
    color: form.color,
    popular: form.popular,
    active: form.active,
    items: form.items
      .filter((item) => item.itemName.trim() !== "")
      .map((item) => ({
        itemName: item.itemName.trim(),
        itemType: item.itemType,
      })),
  });


  /* =========================================================
     SAVE (create or update)
     ========================================================= */

  const handleSubmit = async (event) => {
    event.preventDefault();

    /* Guard against a double submit while the first is in flight. */
    if (isSaving) {
      return;
    }

    const validationError = validate();
    if (validationError) {
      setFormError(validationError);
      return;
    }

    setIsSaving(true);
    setFormError("");
    clearFeedback();

    try {
      const payload = buildPayload();
      const saved =
        editingId === null
          ? await createServicePackage(payload)
          : await updateServicePackage(editingId, payload);

      setShowModal(false);
      setSuccessMessage(
        `${saved?.name || form.name.trim()} ${
          editingId === null ? "created" : "updated"
        } successfully.`
      );
      refreshList();
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


  /* =========================================================
     ACTIVATE / DEACTIVATE

     There is no dedicated endpoint: setServicePackageActive() does
     a full PUT of the record it is handed. It must therefore be
     given the whole package, so the untouched response DTO is
     passed straight through rather than the trimmed card view.
     ========================================================= */

  const handleToggleActive = async (pkg) => {
    if (savingId !== null) {
      return;
    }

    clearFeedback();
    setSavingId(pkg.id);

    try {
      await setServicePackageActive(
        { ...pkg.raw, items: toWritableItems(pkg.raw.items) },
        !pkg.active
      );
      setSuccessMessage(
        `${pkg.name} ${pkg.active ? "deactivated" : "activated"} successfully.`
      );
      refreshList();
    } catch (error) {
      setActionError(
        error instanceof Error
          ? error.message
          : "Unable to change the package status."
      );
    } finally {
      setSavingId(null);
    }
  };


  const packages = list.data;
  const isLoading = list.status === "loading";
  const isEditing = editingId !== null;


  /* =========================================================
     APPLY PACKAGE

     The dialog is opened from the card and writes through
     applyServicePackage(); nothing is applied optimistically. The
     success message is only set from the backend's returned row,
     so "applied" always means the server confirmed a write.
     ========================================================= */

  const openApplyModal = async (pkg) => {
    clearFeedback();
    setApplyTarget(pkg);
    setApplyForm({ customerId: "", vehicleId: "" });
    setApplyError("");
    setCustomerQuery("");
    setApplyContext({
      customersStatus: "loading",
      customers: [],
      customersError: "",
      vehiclesStatus: "idle",
      vehicles: [],
      vehiclesError: "",
    });

    try {
      const customers = await getCustomers();
      setApplyContext((current) => ({
        ...current,
        customersStatus: "success",
        customers: Array.isArray(customers) ? customers : [],
        customersError: "",
      }));
    } catch (error) {
      setApplyContext((current) => ({
        ...current,
        customersStatus: "error",
        customersError:
          error instanceof Error ? error.message : "Unable to load customers.",
      }));
    }
  };

  const closeApplyModal = () => {
    /* An in-flight apply must not be dismissed out from under itself. */
    if (isApplying) {
      return;
    }
    setApplyTarget(null);
    setApplyError("");
  };

  /** Changing customer reloads that customer's vehicles and clears the pick. */
  const handleApplyCustomerChange = async (event) => {
    const customerId = event.target.value;

    setApplyError("");
    setApplyForm((current) => ({ ...current, customerId, vehicleId: "" }));

    if (!customerId) {
      setApplyContext((current) => ({
        ...current,
        vehiclesStatus: "idle",
        vehicles: [],
        vehiclesError: "",
      }));
      return;
    }

    setApplyContext((current) => ({
      ...current,
      vehiclesStatus: "loading",
      vehicles: [],
      vehiclesError: "",
    }));

    try {
      const vehicles = await getCustomerVehicles(customerId);
      setApplyContext((current) => ({
        ...current,
        vehiclesStatus: "success",
        vehicles: Array.isArray(vehicles) ? vehicles : [],
        vehiclesError: "",
      }));
    } catch (error) {
      setApplyContext((current) => ({
        ...current,
        vehiclesStatus: "error",
        vehicles: [],
        vehiclesError:
          error instanceof Error ? error.message : "Unable to load vehicles.",
      }));
    }
  };

  const handleApplySubmit = async (event) => {
    event.preventDefault();

    /* Guard against a double submit while the first is in flight. */
    if (isApplying || !applyTarget) {
      return;
    }

    if (!applyForm.customerId) {
      setApplyError("Select a customer.");
      return;
    }

    if (!applyForm.vehicleId) {
      setApplyError(
        applyContext.vehicles.length === 0 && applyContext.vehiclesStatus === "success"
          ? "That customer has no vehicles on record."
          : "Select a vehicle."
      );
      return;
    }

    setIsApplying(true);
    setApplyError("");
    clearFeedback();

    try {
      const saved = await applyServicePackage({
        servicePackageId: applyTarget.id,
        customerId: Number(applyForm.customerId),
        vehicleId: Number(applyForm.vehicleId),
      });

      // Success is only reported from the row the backend returned.
      setApplyTarget(null);
      setSuccessMessage(
        `${saved?.packageName || applyTarget.name} applied to ${
          saved?.vehicleRegistrationNo || "the vehicle"
        }.`
      );

      /* Re-read both the catalogue and the applications so the card count and
         the dialog's next open reflect what is actually stored. */
      refreshList();
    } catch (error) {
      setApplyError(
        error instanceof Error
          ? error.message
          : "Unable to apply the service package."
      );
    } finally {
      setIsApplying(false);
    }
  };


  return (
    <div className="sa-page sa-page--packages">

      {/* =========================
          PAGE HEADER
          The reference keeps this page at breadcrumb level: the
          catalogue name lives in the top bar, so there is no large
          title and no prominent action here. Creating a package is
          still available from the compact action above the grid.
      ========================= */}


      {/* =========================
          FEEDBACK
      ========================= */}
      {successMessage && (
        <div className="sa-success" role="status">
          {successMessage}
        </div>
      )}

      {actionError && (
        <div className="sa-banner" role="alert">
          {actionError}
        </div>
      )}


      {/* =========================
          STATISTICS
      ========================= */}
      <div className="sa-stats">

        <div className="sa-stat">
          <div className="sa-stat-label">Active Packages</div>
          <div className="sa-stat-value">
            {isLoading && packages.length === 0 ? NOT_PROVIDED : activeCount}
          </div>
        </div>

        {/* No subscription or AMC purchase data is persisted anywhere,
            so this stays unavailable rather than fabricated. */}
        <div className="sa-stat">
          <div className="sa-stat-label">AMC Customers</div>
          <div className="sa-stat-value" style={{ color: "var(--sa-lime)" }}>
            {NOT_PROVIDED}
          </div>
          <div className="sa-stat-note">not tracked by the backend</div>
        </div>

        {/* Same for revenue: the package API returns no sales. */}
        <div className="sa-stat">
          <div className="sa-stat-label">Monthly Revenue</div>
          <div className="sa-stat-value">{NOT_PROVIDED}</div>
          <div className="sa-stat-note">not tracked by the backend</div>
        </div>

        <div className="sa-stat">
          <div className="sa-stat-label">Avg Package Value</div>
          <div className="sa-stat-value">
            {averagePrice === null ? NOT_PROVIDED : `₹${formatPrice(averagePrice)}`}
          </div>
        </div>

      </div>


      {/* =========================
          CATALOGUE ACTIONS
          The create action this page used to carry in its header.
      ========================= */}
      <div className="sa-packages-toolbar">
        <button
          type="button"
          className="sa-packages-new"
          onClick={openCreateModal}
          disabled={isLoading}
        >
          + NEW PACKAGE
        </button>
      </div>


      {/* =========================
          PACKAGE GRID
      ========================= */}
      <div className="sa-package-grid">

        {isLoading && packages.length === 0 ? (
          <div className="sa-packages-state">Loading packages…</div>
        ) : list.status === "error" ? (
          <div className="sa-packages-state">
            <div className="sa-list-error" role="alert">
              <span>{list.error}</span>
              <button type="button" className="sa-retry" onClick={handleRetry}>
                Retry
              </button>
            </div>
          </div>
        ) : packages.length === 0 ? (
          <div className="sa-packages-state">
            No service packages have been created yet. Use + NEW PACKAGE to
            add the first one.
          </div>
        ) : (
          packages.map((pkg) => {
            const isToggleSaving = savingId === pkg.id;
            const appliedCount = appliedCounts.get(pkg.id) ?? 0;

            return (
              <div
                className={`sa-package sa-pkg--${pkg.color} ${
                  pkg.popular ? "popular" : ""
                } ${pkg.active ? "" : "sa-package-inactive"}`}
                key={pkg.id}
                /* .sa-popular is absolutely positioned, so the card is the
                   containing block for that badge. */
                style={{ position: "relative" }}
              >

                {pkg.popular && <div className="sa-popular">POPULAR</div>}

                {/* A deactivated package stays listed but is marked so it is
                    not read as one an advisor may sell. */}
                {!pkg.active && (
                  <div className="sa-inactive-badge">INACTIVE</div>
                )}

                {/* CARD TOP */}
                <div className="sa-package-head">
                  <div className="sa-package-icon">🔧</div>

                  <div className="sa-package-tag">
                    {pkg.type || NOT_PROVIDED}
                  </div>

                  <div className="sa-package-name">{pkg.name}</div>

                  <div className="sa-package-duration">
                    {pkg.duration || NOT_PROVIDED}
                  </div>

                  {/* Real count of vehicles holding this package. */}
                  {appliedCount > 0 && (
                    <div className="sa-applied-count">
                      Applied to {appliedCount} vehicle{appliedCount === 1 ? "" : "s"}
                    </div>
                  )}
                </div>

                {/* PRICE */}
                <div className="sa-package-price">
                  ₹{formatPrice(pkg.price)}
                </div>

                {/* Optional rate fields — only rendered when the DTO carries
                    them, so an unset rate is never shown as 0%. */}
                {pkg.discountPercentage ? (
                  <div className="sa-package-discount">
                    {pkg.discountPercentage}% discount applied
                  </div>
                ) : null}

                {pkg.gstRate != null && (
                  <div className="sa-package-gst">+ {pkg.gstRate}% GST</div>
                )}

                {/* SERVICES + INCLUDED PARTS */}
                <div className="sa-package-body">
                  {pkg.services.map((service) => (
                    <div
                      className="sa-feature"
                      key={`${pkg.id}-service-${service}`}
                    >
                      {service}
                    </div>
                  ))}

                  {pkg.includes.length > 0 && (
                    <>
                      <div className="sa-includes">Includes</div>

                      {pkg.includes.map((part) => (
                        <span className="sa-chip" key={`${pkg.id}-part-${part}`}>
                          {part}
                        </span>
                      ))}
                    </>
                  )}
                </div>

                {/* CARD ACTIONS */}
                <div className="sa-package-footer">

                  {/* An inactive package is withdrawn from sale, so it is not
                      offered for a new application at all. An active one opens
                      the apply dialog. */}
                  <button
                    type="button"
                    className="sa-apply"
                    onClick={() => void openApplyModal(pkg)}
                    disabled={!pkg.active}
                    title={
                      pkg.active
                        ? "Apply this package to a customer"
                        : "This package is inactive and cannot be applied to a new job card."
                    }
                  >
                    {pkg.active ? "APPLY PACKAGE" : "NOT AVAILABLE"}
                  </button>

                  <button
                    type="button"
                    className="sa-pkg-action"
                    onClick={() => openEditModal(pkg)}
                    disabled={savingId !== null}
                  >
                    Edit
                  </button>

                  <button
                    type="button"
                    className="sa-pkg-action"
                    onClick={() => void handleToggleActive(pkg)}
                    disabled={savingId !== null}
                  >
                    {isToggleSaving
                      ? "SAVING…"
                      : pkg.active
                      ? "Deactivate"
                      : "Activate"}
                  </button>

                </div>

              </div>
            );
          })
        )}

      </div>


      {/* =========================
          NEW / EDIT PACKAGE MODAL
          Reuses the shared .sa-modal-* dialog and the scroll
          variant, so the header and the lime footer stay pinned
          while a long item list scrolls.
      ========================= */}
      {showModal && (
        <div
          className="sa-modal-overlay"
          role="presentation"
          onClick={(event) => {
            /* Only a click on the overlay itself closes; clicks inside
               the dialog are ignored so a save is never dismissed. */
            if (event.target === event.currentTarget) {
              closeModal();
            }
          }}
        >
          <div
            className="sa-modal sa-modal--scroll"
            role="dialog"
            aria-modal="true"
            aria-labelledby="sa-package-modal-title"
          >

            <div className="sa-modal-header">
              <h2 className="sa-modal-title" id="sa-package-modal-title">
                {isEditing ? "EDIT SERVICE PACKAGE" : "NEW SERVICE PACKAGE"}
              </h2>

              <button
                type="button"
                className="sa-modal-close"
                onClick={closeModal}
                disabled={isSaving}
                aria-label="Close"
              >
                ×
              </button>
            </div>

            <form className="sa-modal-form" onSubmit={handleSubmit} noValidate>

              {formError && (
                <p className="sa-modal-error" role="alert">
                  {formError}
                </p>
              )}

              <div className="sa-modal-row">
                <label className="sa-modal-field">
                  <span>Package Name *</span>
                  <input
                    className="sa-modal-input"
                    value={form.name}
                    onChange={updateField("name")}
                    disabled={isSaving}
                    maxLength={255}
                    placeholder="Premium Plus Service"
                  />
                </label>

                <label className="sa-modal-field">
                  <span>Package Code *</span>
                  <input
                    className="sa-modal-input"
                    value={form.type}
                    onChange={updateField("type")}
                    disabled={isSaving}
                    maxLength={255}
                    placeholder="PREM-PLUS"
                  />
                </label>
              </div>

              <div className="sa-modal-row">
                <label className="sa-modal-field">
                  <span>Price (₹) *</span>
                  <input
                    className="sa-modal-input"
                    type="number"
                    value={form.price}
                    onChange={updateField("price")}
                    disabled={isSaving}
                    min="0"
                    step="0.01"
                    placeholder="8999"
                  />
                </label>

                <label className="sa-modal-field">
                  <span>Duration / Validity</span>
                  <input
                    className="sa-modal-input"
                    value={form.duration}
                    onChange={updateField("duration")}
                    disabled={isSaving}
                    maxLength={255}
                    placeholder="4–5 hrs / 12 months"
                  />
                </label>
              </div>

              <div className="sa-modal-row">
                <label className="sa-modal-field">
                  <span>GST Rate (%)</span>
                  <select
                    className="sa-modal-input"
                    value={form.gstRate}
                    onChange={updateField("gstRate")}
                    disabled={isSaving}
                  >
                    <option value="">Not set</option>
                    {GST_RATES.map((rate) => (
                      <option key={rate} value={rate}>
                        {rate}
                      </option>
                    ))}
                  </select>
                </label>

                <label className="sa-modal-field">
                  <span>Discount (%)</span>
                  <input
                    className="sa-modal-input"
                    type="number"
                    value={form.discountPercentage}
                    onChange={updateField("discountPercentage")}
                    disabled={isSaving}
                    min="0"
                    max="100"
                    step="0.01"
                    placeholder="0"
                  />
                </label>
              </div>

              {/* COLOUR — the same six tokens the backend accepts. */}
              <fieldset className="sa-modal-group">
                <legend className="sa-modal-legend">Package Colour (for UI)</legend>

                <div className="sa-color-row">
                  {ACCENT_COLORS.map((color) => (
                    <button
                      key={color}
                      type="button"
                      className={`sa-color-swatch sa-color-swatch--${color} ${
                        form.color === color ? "selected" : ""
                      }`}
                      onClick={() => {
                        setFormError("");
                        setForm((current) => ({ ...current, color }));
                      }}
                      disabled={isSaving}
                      aria-label={color}
                      aria-pressed={form.color === color}
                    />
                  ))}
                </div>
              </fieldset>

              {/* INCLUDED ITEMS — one row per PackageItemRequestDTO. */}
              <fieldset className="sa-modal-group">
                <legend className="sa-modal-legend">
                  Included Services &amp; Parts
                </legend>

                <div className="sa-item-list">
                  {form.items.map((item, index) => (
                    <div className="sa-item-row" key={index}>
                      <input
                        className="sa-modal-input"
                        value={item.itemName}
                        onChange={(event) =>
                          updateItem(index, "itemName", event.target.value)
                        }
                        disabled={isSaving}
                        maxLength={255}
                        placeholder="Engine oil change"
                        aria-label={`Item ${index + 1} name`}
                      />

                      <select
                        className="sa-modal-input sa-item-type"
                        value={item.itemType}
                        onChange={(event) =>
                          updateItem(index, "itemType", event.target.value)
                        }
                        disabled={isSaving}
                        aria-label={`Item ${index + 1} type`}
                      >
                        {ITEM_TYPES.map((type) => (
                          <option key={type.value} value={type.value}>
                            {type.label}
                          </option>
                        ))}
                      </select>

                      <button
                        type="button"
                        className="sa-item-remove"
                        onClick={() => removeItem(index)}
                        disabled={isSaving}
                        aria-label={`Remove item ${index + 1}`}
                        title="Remove item"
                      >
                        ×
                      </button>
                    </div>
                  ))}
                </div>

                <button
                  type="button"
                  className="sa-item-add"
                  onClick={addItem}
                  disabled={isSaving}
                >
                  + ADD ITEM
                </button>

                <p className="sa-modal-note">
                  Services render as a ticked list on the card, parts as
                  "Includes" chips. Removing every row clears the package's
                  contents.
                </p>
              </fieldset>

              <label className="sa-modal-check">
                <input
                  type="checkbox"
                  checked={form.popular}
                  onChange={updateField("popular")}
                  disabled={isSaving}
                />
                <span>Show the &quot;Popular&quot; badge on this package</span>
              </label>

              <label className="sa-modal-check">
                <input
                  type="checkbox"
                  checked={form.active}
                  onChange={updateField("active")}
                  disabled={isSaving}
                />
                <span>Active — offer this package to customers</span>
              </label>

              <div className="sa-modal-footer">
                <button
                  type="submit"
                  className="sa-modal-submit"
                  disabled={isSaving}
                >
                  {isSaving
                    ? "SAVING…"
                    : isEditing
                    ? "SAVE CHANGES"
                    : "CREATE PACKAGE"}
                </button>

                <button
                  type="button"
                  className="sa-modal-cancel"
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
          Picks the customer, then the vehicle belonging to them.
          Nothing is written until the backend confirms it.
      ========================= */}
      {applyTarget !== null && (
        <div
          className="sa-modal-overlay"
          role="presentation"
          onClick={(event) => {
            if (event.target === event.currentTarget) {
              closeApplyModal();
            }
          }}
        >
          <div
            className="sa-modal sa-modal--apply"
            role="dialog"
            aria-modal="true"
            aria-labelledby="sa-apply-package-title"
          >

            <div className="sa-modal-header">
              <h2 className="sa-modal-title" id="sa-apply-package-title">
                APPLY PACKAGE
              </h2>

              <button
                type="button"
                className="sa-modal-close"
                onClick={closeApplyModal}
                disabled={isApplying}
                aria-label="Close"
              >
                ×
              </button>
            </div>

            <form className="sa-modal-form" onSubmit={handleApplySubmit} noValidate>

              {applyError && (
                <p className="sa-modal-error" role="alert">
                  {applyError}
                </p>
              )}

              {/* The terms that will be stored. Shown before applying so the
                  advisor sees exactly what the snapshot will record. */}
              <div className={`sa-apply-summary sa-pkg--${applyTarget.color}`}>
                <div className="sa-apply-summary-name">
                  {applyTarget.name}
                </div>
                <div className="sa-apply-summary-terms">
                  <span>₹{formatPrice(applyTarget.price)}</span>
                  {applyTarget.gstRate != null && <span>+ {applyTarget.gstRate}% GST</span>}
                  {applyTarget.discountPercentage ? (
                    <span>{applyTarget.discountPercentage}% off</span>
                  ) : null}
                  {applyTarget.duration && <span>{applyTarget.duration}</span>}
                </div>
              </div>

              {/* ── CUSTOMER ── */}
              <label className="sa-modal-field">
                <span>Customer *</span>

                <input
                  className="sa-modal-input"
                  type="search"
                  value={customerQuery}
                  onChange={(event) => setCustomerQuery(event.target.value)}
                  disabled={isApplying || applyContext.customersStatus === "loading"}
                  placeholder="Search by name or phone"
                />
              </label>

              {applyContext.customersStatus === "loading" ? (
                <p className="sa-state">Loading customers…</p>
              ) : applyContext.customersStatus === "error" ? (
                <p className="sa-modal-error" role="alert">
                  {applyContext.customersError}
                </p>
              ) : (
                <label className="sa-modal-field">
                  <span>Select a customer</span>
                  <select
                    className="sa-modal-input"
                    value={applyForm.customerId}
                    onChange={handleApplyCustomerChange}
                    disabled={isApplying || filteredCustomers.length === 0}
                  >
                    <option value="">
                      {filteredCustomers.length === 0
                        ? "No customers match your search"
                        : "Select a customer"}
                    </option>
                    {filteredCustomers.map((customer) => (
                      <option key={customer.id} value={customer.id}>
                        {customerLabel(customer)}
                      </option>
                    ))}
                  </select>
                </label>
              )}

              {/* ── VEHICLE — always scoped to the selected customer ── */}
              {!applyForm.customerId ? (
                <p className="sa-modal-note">
                  Select a customer to see the vehicles registered to them.
                </p>
              ) : applyContext.vehiclesStatus === "loading" ? (
                <p className="sa-state">Loading vehicles…</p>
              ) : applyContext.vehiclesStatus === "error" ? (
                <p className="sa-modal-error" role="alert">
                  {applyContext.vehiclesError}
                </p>
              ) : (
                <label className="sa-modal-field">
                  <span>Vehicle *</span>
                  <select
                    className="sa-modal-input"
                    value={applyForm.vehicleId}
                    onChange={(event) => {
                      setApplyError("");
                      setApplyForm((current) => ({
                        ...current,
                        vehicleId: event.target.value,
                      }));
                    }}
                    disabled={isApplying || applyContext.vehicles.length === 0}
                  >
                    <option value="">
                      {applyContext.vehicles.length === 0
                        ? "This customer has no vehicles on record"
                        : "Select a vehicle"}
                    </option>
                    {applyContext.vehicles.map((vehicle) => (
                      <option key={vehicle.id} value={vehicle.id}>
                        {vehicleLabel(vehicle)}
                      </option>
                    ))}
                  </select>
                </label>
              )}

              {/* Advisory, not a lock: the backend applies the same rule and
                  answers 409 if the pair is already on record. */}
              {wouldDuplicate && (
                <p className="sa-modal-note">
                  This package has already been applied to that vehicle. Saving
                  will be rejected by the server.
                </p>
              )}

              {applicationsError && (
                <p className="sa-modal-note">
                  Existing applications could not be read ({applicationsError}),
                  so duplicates are not flagged here. The server still enforces
                  the rule.
                </p>
              )}

              <div className="sa-modal-footer">
                <button
                  type="submit"
                  className="sa-modal-submit"
                  disabled={
                    isApplying ||
                    !applyForm.customerId ||
                    !applyForm.vehicleId ||
                    applyContext.vehiclesStatus !== "success"
                  }
                >
                  {isApplying ? "APPLYING…" : "APPLY PACKAGE"}
                </button>

                <button
                  type="button"
                  className="sa-modal-cancel"
                  onClick={closeApplyModal}
                  disabled={isApplying}
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
