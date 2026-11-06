import React, { useCallback, useEffect, useMemo, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import {
  assignJobTaskMechanic,
  consumeJobCardPart,
  createJobCard,
  createJobCardTask,
  getJobCard,
  getJobCardAppointments,
  getJobCardCustomers,
  getJobCardMechanics,
  getJobCardPartCatalog,
  getJobCardPackageApplications,
  getJobCardParts,
  getJobCardTasks,
  getJobCardVehicles,
  getJobCards,
  updateJobCard,
  updateJobCardTask,
  updateJobTaskNotes,
  updateJobTaskStatus,
} from "../../services/jobCardService";
import { useAuth } from "../../hooks/useAuth";
import "./JobCardWorkspace.css";

const PAGE_SIZE = 12;
const JOB_STATUSES = ["RECEIVED", "INSPECTION", "IN_REPAIR", "QUALITY_CHECK", "DELIVERED"];
const TASK_STATUS_TRANSITIONS = {
  PENDING: ["PENDING", "IN_PROGRESS", "CANCELLED"],
  IN_PROGRESS: ["IN_PROGRESS", "COMPLETED", "CANCELLED"],
  COMPLETED: ["COMPLETED"],
  CANCELLED: ["CANCELLED"],
};
const JOB_STATUS_FILTERS = [
  { label: "ALL", value: "" },
  { label: "RECEIVED", value: "RECEIVED" },
  { label: "INSPECTION", value: "INSPECTION" },
  { label: "REPAIR", value: "IN_REPAIR" },
  { label: "QC", value: "QUALITY_CHECK" },
  { label: "DELIVERED", value: "DELIVERED" },
];
const WORKFLOW_STAGES = [
  { label: "Received", statuses: ["RECEIVED"] },
  { label: "Inspection", statuses: ["INSPECTION"] },
  { label: "In Repair", statuses: ["IN_REPAIR", "IN REPAIR"] },
  { label: "QC", statuses: ["QUALITY_CHECK", "QUALITY CHECK", "QC"] },
  { label: "Delivered", statuses: ["DELIVERED"] },
];
const EMPTY_FORM = {
  customerId: "",
  vehicleId: "",
  appointmentId: "",
  packageApplicationId: "",
  serviceType: "",
  customServiceType: "",
  complaint: "",
  odometerReading: "",
  mechanicId: "",
  estimatedDelivery: "",
  estimatedCost: "",
  technicianNotes: "",
};

const displayStatus = (status) =>
  String(status || "UNKNOWN").replace(/_/g, " ").toLowerCase()
    .replace(/\b\w/g, (letter) => letter.toUpperCase());
const formatMoney = (value) => value == null || value === ""
  ? "—"
  : new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 2 }).format(Number(value));
const formatDate = (value) => {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? "—"
    : new Intl.DateTimeFormat("en-IN", { dateStyle: "medium", timeStyle: "short" }).format(date);
};
const localDateTimeValue = (value) => {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  const localDate = new Date(date.getTime() - date.getTimezoneOffset() * 60000);
  return localDate.toISOString().slice(0, 16);
};
const fullName = (customer) => customer.name || [customer.firstName, customer.lastName].filter(Boolean).join(" ");
const vehicleLabel = (vehicle) =>
  [vehicle.registrationNo, [vehicle.make, vehicle.model].filter(Boolean).join(" ")].filter(Boolean).join(" · ");
const listValue = (data) => Array.isArray(data) ? data : [];
const normalizedJobStatus = (status) => String(status || "").trim().toUpperCase();
const statusTone = (status) => {
  switch (String(status || "").toUpperCase()) {
    case "DELIVERED":
    case "COMPLETED":
      return "complete";
    case "IN_REPAIR":
    case "IN REPAIR":
    case "IN_PROGRESS":
      return "active";
    case "QUALITY_CHECK":
    case "QUALITY CHECK":
    case "QC":
      return "review";
    case "CANCELLED":
      return "cancelled";
    default:
      return "pending";
  }
};
const isDeliveredStatus = (status) => normalizedJobStatus(status) === "DELIVERED";
const jobStageIndex = (status) => {
  const normalized = normalizedJobStatus(status);
  return WORKFLOW_STAGES.findIndex((stage) => stage.statuses.includes(normalized));
};
const isToday = (dateValue) => {
  if (!dateValue) return false;
  const date = new Date(dateValue);
  const now = new Date();
  return !Number.isNaN(date.getTime()) &&
    date.getFullYear() === now.getFullYear() &&
    date.getMonth() === now.getMonth() &&
    date.getDate() === now.getDate();
};

async function loadJobCardPanelData(id) {
  const [job, taskRows, partRows] = await Promise.all([
    getJobCard(id),
    getJobCardTasks(id),
    getJobCardParts(id),
  ]);
  return { job, taskRows, partRows, total: job.totalLabourCost };
}

function JobCardWorkspace({ initialJobId = "", initialCreate = false, ownerLayout = false }) {
  const navigate = useNavigate();
  const location = useLocation();
  const { user } = useAuth();
  const role = String(user?.role || "").replace(/^ROLE_/, "").toUpperCase();
  const canCreateJobCards = ["OWNER", "GARAGE_OWNER", "MANAGER", "SERVICE_ADVISOR"].includes(role);
  const canEditJobCards = ["OWNER", "GARAGE_OWNER", "MANAGER", "SERVICE_ADVISOR", "MECHANIC"].includes(role);
  const canManageTasks = canEditJobCards;
  const canAssignTaskMechanic = ["OWNER", "GARAGE_OWNER", "MANAGER", "SERVICE_ADVISOR"].includes(role);
  const canConsumeParts = ["OWNER", "GARAGE_OWNER", "MANAGER", "SERVICE_ADVISOR", "MECHANIC"].includes(role);
  const [jobCards, setJobCards] = useState([]);
  const [customers, setCustomers] = useState([]);
  const [vehicles, setVehicles] = useState([]);
  const [mechanics, setMechanics] = useState([]);
  const [appointments, setAppointments] = useState([]);
  const [partsCatalog, setPartsCatalog] = useState([]);
  const [lookupErrors, setLookupErrors] = useState({});
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [refreshKey, setRefreshKey] = useState(0);
  const [statusFilter, setStatusFilter] = useState("");
  const [page, setPage] = useState(0);
  const [createOpen, setCreateOpen] = useState(initialCreate && canCreateJobCards);
  const [form, setForm] = useState(EMPTY_FORM);
  const [formError, setFormError] = useState("");
  const [saving, setSaving] = useState(false);
  const [notice, setNotice] = useState("");
  const [selectedId, setSelectedId] = useState(initialJobId || "");
  const [detail, setDetail] = useState(null);
  const [tasks, setTasks] = useState([]);
  const [consumedParts, setConsumedParts] = useState([]);
  const [packageApplications, setPackageApplications] = useState([]);
  const [labourTotal, setLabourTotal] = useState(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState("");
  const [detailSaving, setDetailSaving] = useState(false);
  const [detailForm, setDetailForm] = useState(null);
  const [taskForm, setTaskForm] = useState({ description: "", mechanicId: "", labourCost: "", workNotes: "" });
  const [partForm, setPartForm] = useState({ partId: "", quantity: "1", reason: "" });
  const [detailNotice, setDetailNotice] = useState("");

  useEffect(() => {
    setSelectedId(initialJobId || "");
  }, [initialJobId]);

  const loadJobCards = useCallback(async () => {
    setLoading(true);
    setLoadError("");
    try {
      setJobCards(listValue(await getJobCards()));
    } catch (error) {
      setLoadError(error instanceof Error ? error.message : "Unable to load job cards.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadJobCards();
  }, [loadJobCards, refreshKey]);

  useEffect(() => {
    let active = true;
    const loads = [
      ["customers", getJobCardCustomers, setCustomers],
      ["vehicles", getJobCardVehicles, setVehicles],
      ["mechanics", getJobCardMechanics, setMechanics],
      ["appointments", getJobCardAppointments, setAppointments],
      ["parts", getJobCardPartCatalog, setPartsCatalog],
      ["packageApplications", getJobCardPackageApplications, setPackageApplications],
    ];
    Promise.all(loads.map(async ([key, load, setValue]) => {
      try {
        const data = await load();
        if (active) setValue(listValue(data));
      } catch (error) {
        if (active) {
          setLookupErrors((previous) => ({
            ...previous,
            [key]: error instanceof Error ? error.message : `Unable to load ${key}.`,
          }));
        }
      }
    }));
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (!selectedId) return undefined;
    let active = true;
    setDetailLoading(true);
    setDetailError("");
    setDetailNotice("");
    loadJobCardPanelData(selectedId).then(({ job, taskRows, partRows, total }) => {
      if (!active) return;
      setDetail(job);
      setTasks(listValue(taskRows));
      setConsumedParts(listValue(partRows));
      setLabourTotal(total);
      setDetailForm({
        status: job.status || "RECEIVED",
        technicianNotes: job.technicianNotes || "",
        estimatedDelivery: localDateTimeValue(job.estimatedDelivery),
        estimatedCost: job.estimatedCost ?? "",
        packageApplicationId: job.packageApplicationId ? String(job.packageApplicationId) : "",
      });
    }).catch((error) => {
      if (active) setDetailError(error instanceof Error ? error.message : "Unable to load job card details.");
    }).finally(() => {
      if (active) setDetailLoading(false);
    });
    return () => { active = false; };
  }, [selectedId, refreshKey]);

  const serviceTypes = [...new Set(jobCards.map((job) => job.serviceType).filter(Boolean))].sort();
  const appointmentServiceTypes = appointments.map((appointment) => appointment.serviceType).filter(Boolean);
  const selectableServiceTypes = [...new Set([...serviceTypes, ...appointmentServiceTypes])].sort();
  const totalToday = jobCards.filter((job) => isToday(job.assignedDate || job.createdAt)).length;
  const inRepairCount = jobCards.filter((job) =>
    ["IN_REPAIR", "IN REPAIR"].includes(normalizedJobStatus(job.status))
  ).length;
  const qualityCheckCount = jobCards.filter((job) =>
    ["QUALITY_CHECK", "QUALITY CHECK", "QC"].includes(normalizedJobStatus(job.status))
  ).length;
  const deliveredCount = jobCards.filter((job) => isDeliveredStatus(job.status)).length;
  const jobsWithDeliveryDate = jobCards.filter((job) =>
    job.estimatedDelivery && Number.isFinite(new Date(job.estimatedDelivery).getTime())
  );
  const overdueCount = jobsWithDeliveryDate.filter((job) =>
    !isDeliveredStatus(job.status) &&
    new Date(job.estimatedDelivery).getTime() < Date.now()
  ).length;
  const overdueIsPartial = jobsWithDeliveryDate.length < jobCards.length;
  const availableVehicles = vehicles.filter((vehicle) =>
    !form.customerId || String(vehicle.customerId) === String(form.customerId)
  );

  /**
   * Packages that could be attached to a job card for the selected vehicle.
   *
   * Narrowed to the chosen vehicle, because that is the only link the server
   * will accept: an application names its own customer and vehicle and a
   * mismatch is rejected with a 409. Filtering here is a convenience so an
   * invalid choice is never offered — not a substitute for that check.
   */
  const availablePackages = packageApplications.filter((application) =>
    !form.vehicleId || String(application.vehicleId) === String(form.vehicleId)
  );

  /** Packages already held by the vehicle on the open detail modal. */
  const detailPackages = packageApplications.filter((application) =>
    detail && String(application.vehicleId) === String(detail.vehicleId)
  );

  const closeCreate = () => {
    setCreateOpen(false);
    setForm(EMPTY_FORM);
    setFormError("");
    if (initialCreate) navigate("/job-cards");
  };
  const closeDetail = () => {
    setSelectedId("");
    setDetail(null);
    setDetailForm(null);
    setDetailError("");
    if (ownerLayout && initialJobId) {
      navigate(location.pathname.startsWith("/developer")
        ? "/developer/jobcards"
        : "/garage-owner/jobcards");
    } else if (!ownerLayout && initialJobId) {
      navigate("/job-cards");
    }
  };
  const openDetails = (id) => {
    navigate(ownerLayout
      ? `${location.pathname.startsWith("/developer") ? "/developer" : "/garage-owner"}/jobcards/${id}`
      : `/job-cards/${id}`);
  };
  const changeForm = (event) => {
    const { name, value } = event.target;
    if (name === "appointmentId" && value) {
      const appointment = appointments.find((item) => String(item.id) === value);
      if (appointment) {
        setForm((previous) => ({
          ...previous,
          appointmentId: value,
          customerId: appointment.customerId ? String(appointment.customerId) : previous.customerId,
          vehicleId: appointment.vehicleId ? String(appointment.vehicleId) : "",
          serviceType: appointment.serviceType || previous.serviceType,
          // The appointment fixes the vehicle, so any package picked for the
          // previous vehicle can no longer be the right one.
          packageApplicationId: "",
        }));
        return;
      }
    }
    setForm((previous) => ({
      ...previous,
      [name]: value,
      ...(name === "customerId" ? { vehicleId: "", appointmentId: "", serviceType: "", customServiceType: "" } : {}),
      // A package belongs to a vehicle. Changing either one clears the choice
      // rather than leaving an id that the server would reject.
      ...(name === "customerId" || name === "vehicleId" ? { packageApplicationId: "" } : {}),
    }));
  };

  const submitCreate = async (event) => {
    event.preventDefault();
    setFormError("");
    const serviceType = form.serviceType === "__OTHER__"
      ? form.customServiceType.trim()
      : form.serviceType.trim();
    if (!form.customerId || !form.vehicleId || !serviceType) {
      setFormError("Customer, vehicle, and service type are required.");
      return;
    }
    const odometerReading = form.odometerReading === "" ? null : Number(form.odometerReading);
    const estimatedCost = form.estimatedCost === "" ? null : Number(form.estimatedCost);
    if (odometerReading !== null && (!Number.isInteger(odometerReading) || odometerReading < 0)) {
      setFormError("Odometer reading must be a whole number greater than or equal to zero.");
      return;
    }
    if (estimatedCost !== null && (!Number.isFinite(estimatedCost) || estimatedCost < 0)) {
      setFormError("Estimated cost must be a non-negative amount.");
      return;
    }
    if (form.estimatedDelivery) {
      const deliveryTime = new Date(form.estimatedDelivery).getTime();
      if (!Number.isFinite(deliveryTime) || deliveryTime <= Date.now()) {
        setFormError("Estimated delivery must be a valid future date and time.");
        return;
      }
    }
    const payload = {
      customerId: Number(form.customerId),
      vehicleId: Number(form.vehicleId),
      appointmentId: form.appointmentId ? Number(form.appointmentId) : null,
      // Omitted rather than sent as null: the server reads an absent id as
      // "not mentioned", so a job card raised without a package stays
      // unlinked instead of being given a blank one.
      ...(form.packageApplicationId ? { packageApplicationId: Number(form.packageApplicationId) } : {}),
      serviceType,
      complaint: form.complaint.trim() || null,
      odometerReading,
      mechanicId: form.mechanicId ? Number(form.mechanicId) : null,
      estimatedDelivery: form.estimatedDelivery
        ? `${form.estimatedDelivery}:00`
        : null,
      estimatedCost,
      technicianNotes: form.technicianNotes.trim() || null,
    };
    setSaving(true);
    try {
      const created = await createJobCard(payload);
      setNotice(`Job card ${created.jobCardNumber || `#${created.id}`} created successfully.`);
      closeCreate();
      setPage(0);
      setRefreshKey((value) => value + 1);
    } catch (error) {
      setFormError(error instanceof Error ? error.message : "Unable to create job card.");
    } finally {
      setSaving(false);
    }
  };

  const saveJobCard = async (event) => {
    event.preventDefault();
    if (!detail || !detailForm) return;
    if (detailForm.status === "DELIVERED" && detail.status !== "DELIVERED" &&
        !window.confirm("Mark this job card as delivered? This records its completion date.")) {
      return;
    }
    const editedCost = detailForm.estimatedCost === "" ? null : Number(detailForm.estimatedCost);
    if (editedCost !== null && (!Number.isFinite(editedCost) || editedCost < 0)) {
      setDetailError("Estimated cost must be a non-negative amount.");
      return;
    }
    const initialDelivery = localDateTimeValue(detail.estimatedDelivery);
    if (detailForm.estimatedDelivery && detailForm.estimatedDelivery !== initialDelivery) {
      const deliveryTime = new Date(detailForm.estimatedDelivery).getTime();
      if (!Number.isFinite(deliveryTime) || deliveryTime <= Date.now()) {
        setDetailError("Estimated delivery must be a valid future date and time.");
        return;
      }
    }
    setDetailSaving(true);
    setDetailError("");
    setDetailNotice("");
    // Resolved against the job card's own vehicle so a stale selection in the
    // dropdown can never be submitted against the wrong car.
    const linkedPackageId = detailForm.packageApplicationId || "";
    const linkablePackage = detailPackages.find(
      (application) => String(application.id) === String(linkedPackageId)
    );
    try {
      const updated = await updateJobCard(detail.id, {
        customerId: detail.customerId,
        vehicleId: detail.vehicleId,
        serviceType: detail.serviceType,
        mechanicId: detail.mechanicId ?? null,
        appointmentId: detail.appointmentId ?? null,
        ...(linkablePackage ? { packageApplicationId: linkablePackage.id } : {}),
        complaint: detail.complaint || null,
        technicianNotes: detailForm.technicianNotes.trim() || null,
        odometerReading: detail.odometerReading ?? null,
        estimatedDelivery: detailForm.estimatedDelivery ? `${detailForm.estimatedDelivery}:00` : null,
        estimatedCost: editedCost,
        status: detailForm.status,
      });
      setDetail(updated);
      setDetailForm((previous) => ({ ...previous, status: updated.status }));
      setDetailNotice("Job card changes saved.");
      setRefreshKey((value) => value + 1);
    } catch (error) {
      setDetailError(error instanceof Error ? error.message : "Unable to update this job card.");
    } finally {
      setDetailSaving(false);
    }
  };

  const refreshDetails = async (confirmedAction) => {
    if (!selectedId) return;
    setDetailError("");
    try {
      const { job, taskRows, partRows, total } = await loadJobCardPanelData(selectedId);
      setDetail(job);
      setTasks(listValue(taskRows));
      setConsumedParts(listValue(partRows));
      setLabourTotal(total);
      setDetailForm((previous) => previous ? {
        ...previous,
        status: job.status || previous.status,
        technicianNotes: job.technicianNotes || "",
      } : previous);
      setRefreshKey((value) => value + 1);
    } catch (error) {
      const reason = error instanceof Error ? error.message : "Refresh request failed.";
      setDetailError(`${confirmedAction} The change was saved, but job card data could not be refreshed: ${reason}`);
    }
  };

  const submitTask = async (event) => {
    event.preventDefault();
    if (!taskForm.description.trim()) return;
    const labourCost = taskForm.labourCost === "" ? null : Number(taskForm.labourCost);
    if (labourCost !== null && (!Number.isFinite(labourCost) || labourCost < 0)) {
      setDetailError("Labour cost must be a non-negative amount.");
      return;
    }
    setDetailSaving(true);
    setDetailError("");
    setDetailNotice("");
    try {
      await createJobCardTask(detail.id, {
        description: taskForm.description.trim(),
        mechanicId: taskForm.mechanicId ? Number(taskForm.mechanicId) : null,
        labourCost,
        workNotes: taskForm.workNotes.trim() || null,
      });
      setTaskForm({ description: "", mechanicId: "", labourCost: "", workNotes: "" });
      await refreshDetails("Repair task created.");
      setDetailNotice("Repair task added.");
    } catch (error) {
      setDetailError(error instanceof Error ? error.message : "Unable to add repair task.");
    } finally {
      setDetailSaving(false);
    }
  };

  const changeTaskStatus = async (task, status) => {
    if (["COMPLETED", "CANCELLED"].includes(status) &&
        !window.confirm(`Set this repair task to ${displayStatus(status)}?`)) {
      return;
    }
    setDetailError("");
    try {
      await updateJobTaskStatus(task.id, status);
      await refreshDetails("Task status updated.");
      setDetailNotice("Task status updated.");
    } catch (error) {
      setDetailError(error instanceof Error ? error.message : "Unable to update task status.");
    }
  };

  const saveTask = async (task, editedTask) => {
    const labourCost = editedTask.labourCost === "" ? null : Number(editedTask.labourCost);
    if (labourCost !== null && (!Number.isFinite(labourCost) || labourCost < 0)) {
      setDetailError("Labour cost must be a non-negative amount.");
      return;
    }
    setDetailError("");
    try {
      await updateJobCardTask(task.id, {
        description: editedTask.description.trim(),
        mechanicId: task.mechanicId ?? null,
        status: task.status,
        labourCost,
        workNotes: task.workNotes || null,
      });
      await refreshDetails("Repair task updated.");
      setDetailNotice("Repair task updated.");
    } catch (error) {
      setDetailError(error instanceof Error ? error.message : "Unable to update repair task.");
    }
  };

  const changeTaskNotes = async (task, workNotes) => {
    setDetailError("");
    try {
      await updateJobTaskNotes(task.id, workNotes);
      await refreshDetails("Task notes saved.");
      setDetailNotice("Task notes saved.");
    } catch (error) {
      setDetailError(error instanceof Error ? error.message : "Unable to save task notes.");
    }
  };

  const changeTaskMechanic = async (task, mechanicId) => {
    setDetailError("");
    try {
      await assignJobTaskMechanic(task.id, mechanicId ? Number(mechanicId) : null);
      await refreshDetails("Task assignment updated.");
      setDetailNotice("Task assignment updated.");
    } catch (error) {
      setDetailError(error instanceof Error ? error.message : "Unable to update task assignment.");
    }
  };

  const submitPart = async (event) => {
    event.preventDefault();
    const quantity = Number(partForm.quantity);
    if (!partForm.partId || !Number.isInteger(quantity) || quantity <= 0) {
      setDetailError("Part quantity must be a positive whole number.");
      return;
    }
    const partName = partsCatalog.find((part) => String(part.id) === partForm.partId)?.name || "the selected part";
    if (!window.confirm(`Record consumption of ${partForm.quantity} × ${partName}? This will reduce inventory stock.`)) {
      return;
    }
    setDetailSaving(true);
    setDetailError("");
    setDetailNotice("");
    try {
      await consumeJobCardPart(detail.id, partForm.partId, partForm.quantity, partForm.reason.trim());
      setPartForm({ partId: "", quantity: "1", reason: "" });
      try {
        setPartsCatalog(listValue(await getJobCardPartCatalog()));
        setLookupErrors((previous) => ({ ...previous, parts: "" }));
      } catch (error) {
        const reason = error instanceof Error ? error.message : "Inventory refresh failed.";
        setLookupErrors((previous) => ({ ...previous, parts: reason }));
        setDetailError(`Part consumption was recorded, but available inventory could not be refreshed: ${reason}`);
      }
      await refreshDetails("Part consumption recorded.");
      setDetailNotice("Part consumption recorded.");
    } catch (error) {
      setDetailError(error instanceof Error ? error.message : "Unable to record part consumption.");
    } finally {
      setDetailSaving(false);
    }
  };

  const filteredJobs = useMemo(() => {
    if (!statusFilter) return jobCards;
    return jobCards.filter((job) => {
      const status = normalizedJobStatus(job.status);
      return status === statusFilter ||
        (statusFilter === "IN_REPAIR" && status === "IN REPAIR") ||
        (statusFilter === "QUALITY_CHECK" && ["QUALITY CHECK", "QC"].includes(status));
    });
  }, [jobCards, statusFilter]);
  const totalPages = Math.max(1, Math.ceil(filteredJobs.length / PAGE_SIZE));
  const visibleJobs = filteredJobs.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE);
  const selectStatusFilter = (value) => {
    setStatusFilter(value);
    setPage(0);
  };

  return (
    <section className={`job-workspace${ownerLayout ? " owner-workspace" : ""}`}>
      <div className="job-workspace-heading">
        <div>
          <h1>JOB CARDS</h1>
        </div>
        {canCreateJobCards && <button type="button" className="job-primary-button" onClick={() => setCreateOpen(true)}>
          + NEW JOB CARD
        </button>}
      </div>

      {notice && <div className="job-notice" role="status">{notice}</div>}
      {initialCreate && !canCreateJobCards && (
        <div className="job-form-error" role="alert">
          Your account does not have permission to create job cards. Contact a garage owner or service advisor.
        </div>
      )}

      <section className="job-kpis" aria-label="Job card summary">
        <article><span>TOTAL TODAY</span><strong>{loading ? "…" : loadError ? "—" : totalToday}</strong></article>
        <article><span>IN REPAIR</span><strong className="lime">{loading ? "…" : loadError ? "—" : inRepairCount}</strong></article>
        <article><span>QC</span><strong>{loading ? "…" : loadError ? "—" : qualityCheckCount}</strong></article>
        <article><span>DELIVERED</span><strong>{loading ? "…" : loadError ? "—" : deliveredCount}</strong></article>
        <article title={overdueIsPartial ? "Only job cards with an estimated delivery date can be evaluated." : "Past estimated delivery, excluding delivered job cards."}>
          <span>OVERDUE{overdueIsPartial ? " *" : ""}</span>
          <strong>{loading ? "…" : loadError ? "—" : jobsWithDeliveryDate.length || jobCards.length === 0 ? overdueCount : "—"}</strong>
        </article>
      </section>
      {overdueIsPartial && !loading && !loadError && (
        <p className="job-metric-note">
          {jobsWithDeliveryDate.length
            ? "* Overdue is counted only for job cards with an estimated delivery date."
            : "Overdue is unavailable until estimated delivery dates are recorded."}
        </p>
      )}

      <section className="job-table-panel">
        <div className="job-status-filters" aria-label="Filter job cards by status">
          {JOB_STATUS_FILTERS.map((filter) => (
            <button
              key={filter.label}
              type="button"
              className={statusFilter === filter.value ? "active" : ""}
              aria-pressed={statusFilter === filter.value}
              onClick={() => selectStatusFilter(filter.value)}
            >
              {filter.label}
            </button>
          ))}
        </div>

        {loadError ? (
          <div className="job-state job-error" role="alert">
            <strong>Could not load job cards</strong><p>{loadError}</p>
            <button type="button" className="job-secondary-button" onClick={() => setRefreshKey((value) => value + 1)}>Retry</button>
          </div>
        ) : loading ? (
          <div className="job-state" role="status">Loading job cards…</div>
        ) : filteredJobs.length === 0 ? (
          <div className="job-state">
            <strong>{jobCards.length ? "No matching job cards" : "No job cards yet"}</strong>
            <p>{jobCards.length ? "Try selecting another status." : "Create a job card to start recording repair work."}</p>
          </div>
        ) : (
          <>
            <div className="job-card-list">
              {visibleJobs.map((job) => (
                <article className="job-list-card" key={job.id}>
                  <div className="job-list-card-main">
                    <div className="job-card-identity">
                      <strong>{job.jobCardNumber || `#${job.id}`}</strong>
                      <span>{job.customerName || "Customer unavailable"}</span>
                      <small>{job.vehicleInfo || "Vehicle unavailable"}</small>
                    </div>
                    <div className="job-card-service">
                      <span>SERVICE</span>
                      <strong>{job.serviceType || "—"}</strong>
                      <small>{job.mechanicName || "Mechanic unassigned"}</small>
                    </div>
                    <div className="job-card-summary">
                      <div className="job-card-status-actions">
                        <span className={`job-status ${statusTone(job.status)}`}>{displayStatus(job.status)}</span>
                        <button type="button" className="job-row-action" onClick={() => openDetails(job.id)}>DETAILS <span aria-hidden="true">→</span></button>
                      </div>
                      <div className="job-card-delivery">
                        <small><b>EXPECTED DELIVERY</b><span>{job.estimatedDelivery ? formatDate(job.estimatedDelivery) : "Not scheduled"}</span></small>
                        <strong>{formatMoney(job.estimatedCost)}</strong>
                      </div>
                    </div>
                  </div>
                  <JobProgress status={job.status} />
                </article>
              ))}
            </div>
            <div className="job-pagination">
              <span>{filteredJobs.length} job card{filteredJobs.length === 1 ? "" : "s"}</span>
              <div>
                <button type="button" disabled={page === 0} onClick={() => setPage((value) => Math.max(0, value - 1))}>Previous</button>
                <span>Page {page + 1} of {totalPages}</span>
                <button type="button" disabled={page + 1 >= totalPages} onClick={() => setPage((value) => Math.min(totalPages - 1, value + 1))}>Next</button>
              </div>
            </div>
          </>
        )}
      </section>

      {createOpen && canCreateJobCards && (
        <div className="job-modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget && !saving) closeCreate(); }}>
          <section className="job-modal job-create-modal" role="dialog" aria-modal="true" aria-labelledby="job-create-title">
            <header className="job-modal-header">
              <div><span>WORKSHOP OPERATIONS</span><h2 id="job-create-title">NEW JOB CARD</h2></div>
              <button type="button" className="job-modal-close" onClick={closeCreate} aria-label="Close form" disabled={saving}>×</button>
            </header>
            <form className="job-modal-form" onSubmit={submitCreate}>
              <div className="job-form-grid job-create-fields">
                <label>FROM APPOINTMENT <select name="appointmentId" value={form.appointmentId} onChange={changeForm}>
                  <option value="">Create without appointment</option>
                  {appointments.map((appointment) => <option key={appointment.id} value={appointment.id}>
                    {`#${appointment.id} · ${appointment.customerName || "Customer"} · ${formatDate(appointment.appointmentAt)}`}
                  </option>)}
                </select></label>
                <label>CUSTOMER <select name="customerId" value={form.customerId} onChange={changeForm} required>
                  <option value="">Select customer</option>
                  {customers.map((customer) => <option key={customer.id} value={customer.id}>{fullName(customer)}</option>)}
                </select></label>
                <label>VEHICLE <select name="vehicleId" value={form.vehicleId} onChange={changeForm} required disabled={!form.customerId}>
                  <option value="">{form.customerId ? "Select vehicle" : "Choose customer first"}</option>
                  {availableVehicles.map((vehicle) => <option key={vehicle.id} value={vehicle.id}>{vehicleLabel(vehicle)}</option>)}
                </select></label>
                <label>APPLIED PACKAGE <select name="packageApplicationId" value={form.packageApplicationId} onChange={changeForm}>
                  <option value="">{form.vehicleId
                    ? (availablePackages.length === 0 ? "No applied package for this vehicle" : "Not covered by a package")
                    : "Choose vehicle first"}</option>
                  {availablePackages.map((application) => <option key={application.id} value={application.id}>
                    {`${application.packageName || "Package"} · ${formatMoney(application.packagePrice)}`}
                  </option>)}
                </select></label>
                <label>ODOMETER READING (KM) <input name="odometerReading" type="number" min="0" step="1" value={form.odometerReading} onChange={changeForm} /></label>
                <label>SERVICE TYPE <select name="serviceType" value={form.serviceType} onChange={changeForm} required>
                  <option value="">Select service type</option>
                  {selectableServiceTypes.map((service) => <option key={service} value={service}>{service}</option>)}
                  <option value="__OTHER__">Other / enter manually</option>
                </select></label>
                {form.serviceType === "__OTHER__" && <label>SERVICE TYPE (OTHER) <input name="customServiceType" value={form.customServiceType} onChange={changeForm} maxLength={120} required placeholder="Enter service type" /></label>}
                <label>ASSIGN MECHANIC <select name="mechanicId" value={form.mechanicId} onChange={changeForm}>
                  <option value="">Unassigned</option>
                  {mechanics.map((mechanic) => <option key={mechanic.id} value={mechanic.id}>{mechanic.name}</option>)}
                </select></label>
                <label>CUSTOMER COMPLAINT <textarea name="complaint" rows="3" value={form.complaint} onChange={changeForm} maxLength={2000} /></label>
                <label>ESTIMATED DELIVERY <input name="estimatedDelivery" type="datetime-local" value={form.estimatedDelivery} onChange={changeForm} /></label>
                <label>ESTIMATED COST (₹) <input name="estimatedCost" type="number" min="0" step="0.01" value={form.estimatedCost} onChange={changeForm} /></label>
                <label>TECHNICIAN NOTES <textarea name="technicianNotes" rows="2" value={form.technicianNotes} onChange={changeForm} maxLength={2000} /></label>
              </div>
              {lookupErrors.customers && <p className="job-help-error">Customer choices: {lookupErrors.customers}</p>}
              {lookupErrors.vehicles && <p className="job-help-error">Vehicle choices: {lookupErrors.vehicles}</p>}
              {lookupErrors.mechanics && <p className="job-help-error">Mechanic choices: {lookupErrors.mechanics}</p>}
              {lookupErrors.appointments && <p className="job-help-error">Appointment choices unavailable: {lookupErrors.appointments}</p>}
              {lookupErrors.packageApplications && <p className="job-help-error">Applied package choices unavailable: {lookupErrors.packageApplications}</p>}
              {formError && <p className="job-form-error" role="alert">{formError}</p>}
              <footer className="job-modal-footer">
                <button type="button" className="job-secondary-button" onClick={closeCreate} disabled={saving}>Cancel</button>
                <button type="submit" className="job-primary-button" disabled={saving}>{saving ? "CREATING…" : "CREATE JOB CARD"}</button>
              </footer>
            </form>
          </section>
        </div>
      )}

      {selectedId && (
        <div className="job-modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget && !detailSaving) closeDetail(); }}>
          <section className="job-modal job-detail-modal" role="dialog" aria-modal="true" aria-labelledby="job-detail-title">
            <header className="job-modal-header">
              <div><span>JOB CARD DETAILS</span><h2 id="job-detail-title">{detail?.jobCardNumber || `Job card ${selectedId}`}</h2></div>
              <button type="button" className="job-modal-close" onClick={closeDetail} aria-label="Close details">×</button>
            </header>
            <div className="job-detail-content">
              {detailLoading ? <div className="job-state">Loading job card details…</div> :
                detailError && !detail ? <div className="job-state job-error" role="alert">
                  <strong>Could not load job card details</strong><p>{detailError}</p>
                  <button type="button" className="job-secondary-button" onClick={() => setRefreshKey((value) => value + 1)}>Retry</button>
                </div> :
                  detail && <>
                    <section className="job-detail-summary">
                      <div><span>STATUS</span><strong className={`job-status ${statusTone(detail.status)}`}>{displayStatus(detail.status)}</strong></div>
                      <div><span>CUSTOMER</span><strong>{detail.customerName || "—"}</strong></div>
                      <div><span>VEHICLE</span><strong>{detail.vehicleInfo || "—"}</strong></div>
                      <div><span>SERVICE</span><strong>{detail.serviceType || "—"}</strong></div>
                      <div><span>MECHANIC</span><strong>{detail.mechanicName || "Unassigned"}</strong></div>
                      <div><span>ODOMETER</span><strong>{detail.odometerReading == null ? "—" : `${detail.odometerReading.toLocaleString("en-IN")} km`}</strong></div>
                      <div><span>ESTIMATED COST</span><strong>{formatMoney(detail.estimatedCost)}</strong></div>
                      <div><span>ESTIMATED DELIVERY</span><strong>{formatDate(detail.estimatedDelivery)}</strong></div>
                      <div><span>CREATED</span><strong>{formatDate(detail.createdAt)}</strong></div>
                      <div><span>UPDATED</span><strong>{formatDate(detail.updatedAt)}</strong></div>
                      <div><span>COMPLETED</span><strong>{formatDate(detail.completedDate)}</strong></div>
                    </section>
                    {detail.complaint && <section className="job-detail-section"><h3>Customer complaint</h3><p>{detail.complaint}</p></section>}
                    {detail.inspectionId && <section className="job-detail-section">
                      <h3>Inspection · {displayStatus(detail.inspectionStatus)}</h3>
                      {detail.inspectionItems?.length
                        ? <ul>{detail.inspectionItems.map((item, index) => <li key={item.id || index}>{item.checklistItem || "Inspection finding"}{item.finding ? ` — ${item.finding}` : ""}</li>)}</ul>
                        : <p>No inspection items were returned.</p>}
                    </section>}

                    {/* Linked package. Display only — nothing here is marked
                        complete or consumed, and no price is applied. */}
                    {detail.packageApplicationId && <section className="job-detail-section">
                      <h3>Covered by package · {detail.packageName || "Applied package"}</h3>
                      <p className="job-muted">{[
                        detail.packageType,
                        detail.packageDuration,
                        detail.packagePrice != null ? formatMoney(detail.packagePrice) : null,
                        detail.packageGstRate != null ? `+ ${detail.packageGstRate}% GST` : null,
                      ].filter(Boolean).join(" · ") || "No package terms were recorded."}</p>
                      {detail.packageServices?.length
                        ? <ul>{detail.packageServices.map((service, index) => <li key={`${service}-${index}`}>{service}</li>)}</ul>
                        : <p className="job-muted">This package bundles no service tasks.</p>}
                      <p className="job-muted">Included tasks are shown for reference and are not ticked off by this job card.</p>
                    </section>}

                    {canEditJobCards && <form className="job-detail-section job-detail-edit" onSubmit={saveJobCard}>
                      <h3>Job card updates</h3>
                      <div className="job-form-grid">
                        <label>Status <select value={detailForm?.status || detail.status} onChange={(event) => setDetailForm((previous) => ({ ...previous, status: event.target.value }))}>
                          {detailForm?.status && !JOB_STATUSES.includes(detailForm.status) && (
                            <option value={detailForm.status}>{displayStatus(detailForm.status)}</option>
                          )}
                          {JOB_STATUSES.map((status) => <option key={status} value={status}>{displayStatus(status)}</option>)}
                        </select></label>
                        <label>Estimated delivery <input type="datetime-local" value={detailForm?.estimatedDelivery || ""} onChange={(event) => setDetailForm((previous) => ({ ...previous, estimatedDelivery: event.target.value }))} /></label>
                        <label>Estimated cost (₹) <input type="number" min="0" step="0.01" value={detailForm?.estimatedCost ?? ""} onChange={(event) => setDetailForm((previous) => ({ ...previous, estimatedCost: event.target.value }))} /></label>
                        <label>Applied package <select value={detailForm?.packageApplicationId || ""} onChange={(event) => setDetailForm((previous) => ({ ...previous, packageApplicationId: event.target.value }))}>
                          <option value="">{detail.packageApplicationId ? "Leave package unchanged" : "Not covered by a package"}</option>
                          {detailPackages.map((application) => <option key={application.id} value={application.id}>
                            {`${application.packageName || "Package"} · ${formatMoney(application.packagePrice)}`}
                          </option>)}
                        </select></label>
                        <label className="job-form-full">Technician notes <textarea rows="2" value={detailForm?.technicianNotes || ""} onChange={(event) => setDetailForm((previous) => ({ ...previous, technicianNotes: event.target.value }))} /></label>
                      </div>
                      <button className="job-primary-button" type="submit" disabled={detailSaving}>{detailSaving ? "SAVING…" : "SAVE JOB CARD"}</button>
                    </form>}

                    <section className="job-detail-section">
                      <div className="job-section-title"><h3>Repair tasks</h3><span>{tasks.length} task{tasks.length === 1 ? "" : "s"} · Labour total {formatMoney(labourTotal ?? detail.totalLabourCost)}</span></div>
                      {tasks.length === 0 ? <p className="job-muted">No repair tasks have been recorded.</p> :
                        <div className="job-task-list">{tasks.map((task) => (
                          <TaskRow key={task.id} task={task} mechanics={mechanics} onStatus={changeTaskStatus} onNotes={changeTaskNotes} onMechanic={changeTaskMechanic} onSave={saveTask} canAssignMechanic={canAssignTaskMechanic} canEdit={detail.status !== "DELIVERED"} />
                        ))}</div>}
                      {detail.status !== "DELIVERED" && canManageTasks && <form className="job-inline-form" onSubmit={submitTask}>
                        <h4>Add repair task</h4>
                        <label>Description<input value={taskForm.description} onChange={(event) => setTaskForm((previous) => ({ ...previous, description: event.target.value }))} required maxLength={500} /></label>
                        <div className="job-form-grid">
                          <label>Mechanic<select value={taskForm.mechanicId} onChange={(event) => setTaskForm((previous) => ({ ...previous, mechanicId: event.target.value }))}>
                            <option value="">Unassigned</option>{mechanics.map((mechanic) => <option key={mechanic.id} value={mechanic.id}>{mechanic.name}</option>)}
                          </select></label>
                          <label>Labour cost (₹)<input type="number" min="0" step="0.01" value={taskForm.labourCost} onChange={(event) => setTaskForm((previous) => ({ ...previous, labourCost: event.target.value }))} /></label>
                          <label className="job-form-full">Work notes<textarea rows="2" value={taskForm.workNotes} onChange={(event) => setTaskForm((previous) => ({ ...previous, workNotes: event.target.value }))} /></label>
                        </div>
                        <button className="job-secondary-button" type="submit" disabled={detailSaving}>ADD TASK</button>
                      </form>}
                    </section>

                    <section className="job-detail-section">
                      <div className="job-section-title"><h3>Spare parts</h3><span>Inventory consumption records</span></div>
                      {consumedParts.length === 0 ? <p className="job-muted">No parts have been recorded for this job card.</p> :
                        <div className="job-parts-list">{consumedParts.map((part) => (
                          <div key={part.id}><strong>{part.partName || part.partSku || `Part ${part.partId}`}</strong><span>Qty {part.quantity}</span><span>{formatDate(part.createdAt)}</span>{part.reason && <small>{part.reason}</small>}</div>
                        ))}</div>}
                      {detail.status !== "DELIVERED" && canConsumeParts && <form className="job-inline-form" onSubmit={submitPart}>
                        <h4>Record part consumption</h4>
                        <div className="job-form-grid">
                          <label>Part<select required value={partForm.partId} onChange={(event) => setPartForm((previous) => ({ ...previous, partId: event.target.value }))}>
                            <option value="">Select inventory part</option>{partsCatalog.map((part) => <option key={part.id} value={part.id}>{part.name} · {part.sku} · stock {part.stockQty}</option>)}
                          </select></label>
                          <label>Quantity<input type="number" min="1" step="1" required value={partForm.quantity} onChange={(event) => setPartForm((previous) => ({ ...previous, quantity: event.target.value }))} /></label>
                          <label className="job-form-full">Reason / note<input value={partForm.reason} onChange={(event) => setPartForm((previous) => ({ ...previous, reason: event.target.value }))} /></label>
                        </div>
                        {lookupErrors.parts && <p className="job-help-error">Parts inventory is unavailable: {lookupErrors.parts}</p>}
                        <button className="job-secondary-button" type="submit" disabled={detailSaving || Boolean(lookupErrors.parts) || partsCatalog.length === 0}>RECORD CONSUMPTION</button>
                      </form>}
                    </section>
                  </>}
              {detailError && detail && <div className="job-form-error" role="alert">
                <p>{detailError}</p>
                <button type="button" className="job-row-action" onClick={() => setRefreshKey((value) => value + 1)}>Retry loading details</button>
              </div>}
              {detailNotice && <p className="job-inline-notice" role="status">{detailNotice}</p>}
            </div>
          </section>
        </div>
      )}
    </section>
  );
}

function JobProgress({ status }) {
  const activeIndex = jobStageIndex(status);
  const progressPercent = activeIndex < 0
    ? 0
    : 10 + (activeIndex / (WORKFLOW_STAGES.length - 1)) * 80;
  return (
    <ol
      className="job-progress"
      aria-label={`Repair progress: ${displayStatus(status)}`}
      style={{ "--job-progress": `${progressPercent}%` }}
    >
      {WORKFLOW_STAGES.map((stage, index) => (
        <li key={stage.label} className={index <= activeIndex ? "complete" : ""}>
          <span className="job-progress-dot" aria-hidden="true">{index < activeIndex ? "✓" : index + 1}</span>
          <span>{stage.label}</span>
        </li>
      ))}
    </ol>
  );
}

function TaskRow({ task, mechanics, onStatus, onNotes, onMechanic, onSave, canAssignMechanic, canEdit }) {
  const [notes, setNotes] = useState(task.workNotes || "");
  const [editing, setEditing] = useState(false);
  const [description, setDescription] = useState(task.description || "");
  const [labourCost, setLabourCost] = useState(task.labourCost ?? "");
  useEffect(() => {
    setNotes(task.workNotes || "");
    setDescription(task.description || "");
    setLabourCost(task.labourCost ?? "");
  }, [task]);
  const saveChanges = async () => {
    if (!description.trim()) return;
    await onSave(task, { description, labourCost });
    setEditing(false);
  };
  return (
    <article className="job-task-row">
      <div className="job-task-top">
        {editing
          ? <input aria-label="Task description" value={description} onChange={(event) => setDescription(event.target.value)} />
          : <strong>{task.description}</strong>}
        <span className={`job-status ${statusTone(task.status)}`}>{displayStatus(task.status)}</span>
      </div>
      {editing && <label className="job-task-cost">Labour cost (₹)<input type="number" min="0" step="0.01" value={labourCost} onChange={(event) => setLabourCost(event.target.value)} /></label>}
      <div className="job-task-fields">
        <label>Status<select value={task.status || "PENDING"} onChange={(event) => onStatus(task, event.target.value)} disabled={!canEdit || ["COMPLETED", "CANCELLED"].includes(task.status)}>
          {(TASK_STATUS_TRANSITIONS[task.status] || [task.status || "PENDING"]).map((status) => <option key={status} value={status}>{displayStatus(status)}</option>)}
        </select></label>
        {canAssignMechanic && <label>Assigned mechanic<select value={task.mechanicId || ""} onChange={(event) => onMechanic(task, event.target.value)} disabled={!canEdit}>
          <option value="">Unassigned</option>{mechanics.map((mechanic) => <option key={mechanic.id} value={mechanic.id}>{mechanic.name}</option>)}
        </select></label>}
        {!editing && <span>Labour {formatMoney(task.labourCost)}</span>}
      </div>
      <div className="job-task-notes">
        <input aria-label={`Work notes for ${task.description}`} value={notes} onChange={(event) => setNotes(event.target.value)} placeholder="Work notes" disabled={!canEdit} />
        <button type="button" className="job-row-action" onClick={() => onNotes(task, notes)} disabled={!canEdit}>Save notes</button>
        {canEdit && (editing
          ? <button type="button" className="job-row-action" onClick={saveChanges}>Save task</button>
          : <button type="button" className="job-row-action" onClick={() => setEditing(true)}>Edit task</button>)}
      </div>
    </article>
  );
}

export default JobCardWorkspace;
