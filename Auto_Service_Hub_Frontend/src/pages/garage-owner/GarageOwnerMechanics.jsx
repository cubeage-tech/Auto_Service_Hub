import React, { useCallback, useEffect, useMemo, useState } from "react";
import {
  createMechanic,
  getMechanicAttendance,
  getMechanicPerformance,
  getMechanics,
  updateMechanic,
} from "../../services/mechanicService";
import {
  getJobCard,
  getJobCards,
  updateJobCard,
} from "../../services/jobCardService";
import "./GarageOwnerMechanics.css";

// ── Constants ──────────────────────────────────────────────────────────────────

const ACTIVE_JOB_STATUSES = new Set([
  "RECEIVED",
  "INSPECTION",
  "IN_REPAIR",
  "QUALITY_CHECK",
  "QC",
]);

/** Role options matching the Figma dropdown (SRS 4.6). */
const ROLE_OPTIONS = [
  "Junior Mechanic",
  "Mechanic",
  "Senior Mechanic",
  "Auto Electrician",
  "Body Shop Technician",
  "Supervisor",
];

/**
 * 12 skills in Figma column order — left column first, then right column,
 * so the CSS grid renders them paired exactly as shown in the screenshot.
 * Left col:  Engine Repair, AC Repair,   Electrical, Body Work,   Full Service, Battery
 * Right col: Transmission,  Suspension,  Brakes,     Painting,    Tyres,        Diagnostics
 */
const SKILL_OPTIONS = [
  "Engine Repair", "Transmission",
  "AC Repair",     "Suspension",
  "Electrical",    "Brakes",
  "Body Work",     "Painting",
  "Full Service",  "Tyres",
  "Battery",       "Diagnostics",
];

// ── Helpers ────────────────────────────────────────────────────────────────────

const getLocalDate = (date) => {
  const year  = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day   = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
};

const getRatingCount = (r) => Number(r?.ratingCount) || 0;

/** Empty form state — used for both Add (no id) and as the reset target. */
const emptyForm = () => ({
  name:            "",
  phone:           "",
  role:            "",
  experienceYears: "",
  skills:          [],
  joiningDate:     "",
  salary:          "",
  status:          "ACTIVE",
  governmentId:    "",   // plaintext entered by the user — sent once, never echoed back
});

// ── Component ──────────────────────────────────────────────────────────────────

function GarageOwnerMechanics() {
  const [mechanics,          setMechanics]          = useState([]);
  const [attendance,         setAttendance]          = useState([]);
  const [jobCards,           setJobCards]            = useState([]);
  const [performance,        setPerformance]         = useState([]);
  const [loading,            setLoading]             = useState(true);
  const [errors,             setErrors]              = useState({});
  const [notice,             setNotice]              = useState("");
  const [formMechanic,       setFormMechanic]        = useState(null);
  const [assignmentMechanic, setAssignmentMechanic]  = useState(null);
  const [formData,           setFormData]            = useState(emptyForm());
  const [selectedJobCardId,  setSelectedJobCardId]   = useState("");
  const [saving,             setSaving]              = useState(false);
  const [modalError,         setModalError]          = useState("");
  const [showGovId,          setShowGovId]           = useState(false);

  // ── Data loading ────────────────────────────────────────────────────────────

  const loadData = useCallback(async () => {
    setLoading(true);
    setErrors({});
    const today       = getLocalDate(new Date());
    const periodStart = new Date();
    periodStart.setDate(periodStart.getDate() - 29);
    const from = getLocalDate(periodStart);

    const [mechanicsResult, attendanceResult, jobCardsResult, performanceResult] =
      await Promise.allSettled([
        getMechanics(),
        getMechanicAttendance(today),
        getJobCards(),
        getMechanicPerformance(from, today),
      ]);

    const nextErrors = {};

    if (mechanicsResult.status  === "fulfilled") setMechanics(mechanicsResult.value);
    else { setMechanics([]);   nextErrors.mechanics   = mechanicsResult.reason?.message  || "Mechanics could not be loaded."; }

    if (attendanceResult.status === "fulfilled") setAttendance(attendanceResult.value);
    else { setAttendance([]);  nextErrors.attendance  = attendanceResult.reason?.message || "Attendance could not be loaded."; }

    if (jobCardsResult.status   === "fulfilled") setJobCards(jobCardsResult.value);
    else { setJobCards([]);    nextErrors.jobCards    = jobCardsResult.reason?.message   || "Job assignments could not be loaded."; }

    if (performanceResult.status === "fulfilled") {
      const raw = performanceResult.value;
      setPerformance(Array.isArray(raw) ? raw : []);
    } else {
      setPerformance([]);
      nextErrors.performance = performanceResult.reason?.message || "Ratings could not be loaded.";
    }

    setErrors(nextErrors);
    setLoading(false);
  }, []);

  useEffect(() => { loadData(); }, [loadData]);

  // ── Derived maps ────────────────────────────────────────────────────────────

  const attendanceByMechanic = useMemo(
    () => new Map(attendance.map((r) => [Number(r.mechanicId), r])),
    [attendance],
  );
  const ratingsByMechanic = useMemo(
    () => new Map(performance.map((r) => [Number(r.mechanicId), r])),
    [performance],
  );
  const jobsByMechanic = useMemo(() => {
    const counts = new Map();
    for (const job of jobCards) {
      const mid    = Number(job.mechanicId);
      if (!mid) continue;
      const status = String(job.status || "").toUpperCase();
      const count  = counts.get(mid) || { active: 0, completed: 0 };
      if (status === "DELIVERED")               count.completed += 1;
      else if (ACTIVE_JOB_STATUSES.has(status)) count.active   += 1;
      counts.set(mid, count);
    }
    return counts;
  }, [jobCards]);

  const presentCount = attendance.filter((r) => r.open === true).length;
  const ratedMechanics = performance.filter(
    (r) =>
      getRatingCount(r) > 0 &&
      r.averageCustomerRating !== null &&
      r.averageCustomerRating !== undefined &&
      Number.isFinite(Number(r.averageCustomerRating)),
  );
  const totalRatings = ratedMechanics.reduce((s, r) => s + getRatingCount(r), 0);
  const averageRating =
    totalRatings > 0
      ? ratedMechanics.reduce(
          (s, r) => s + Number(r.averageCustomerRating) * getRatingCount(r), 0,
        ) / totalRatings
      : null;

  // ── Modal open helpers ──────────────────────────────────────────────────────

  const openAddModal = () => {
    setFormData(emptyForm());
    setShowGovId(false);
    setModalError("");
    setFormMechanic({});               // {} = Add mode (no .id)
  };

  const handleEdit = (mechanic) => {
    setFormData({
      name:            mechanic.name            || "",
      phone:           mechanic.phone           || "",
      role:            mechanic.role            || "",
      experienceYears: mechanic.experienceYears ?? "",
      skills:          Array.isArray(mechanic.skills) ? [...mechanic.skills] : [],
      // joining_date arrives as "YYYY-MM-DD" string from JSON — use as-is for date input
      joiningDate:     mechanic.joiningDate     || "",
      // salary may be null for non-admin callers — show blank in that case
      salary:          mechanic.salary != null  ? String(mechanic.salary) : "",
      status:          mechanic.status          || "ACTIVE",
      // Always blank on edit — user must re-enter if they want to update.
      // The backend preserves the existing encrypted value when this is omitted.
      governmentId:    "",
    });
    setShowGovId(false);
    setModalError("");
    setFormMechanic(mechanic);
  };

  // ── Form field handlers ─────────────────────────────────────────────────────

  const handleInputChange = (e) => {
    const { name, value } = e.target;
    setFormData((prev) => ({ ...prev, [name]: value }));
  };

  const handleSkillToggle = (skill) => {
    setFormData((prev) => {
      const exists = prev.skills.includes(skill);
      return {
        ...prev,
        skills: exists
          ? prev.skills.filter((s) => s !== skill)
          : [...prev.skills, skill],
      };
    });
  };

  // ── Save mechanic ───────────────────────────────────────────────────────────

  const handleMechanicSave = async (e) => {
    e.preventDefault();
    if (saving) return;

    // ── Validation ──────────────────────────────────────────────────────────
    const name = formData.name.trim();
    if (!name) { setModalError("Full name is required."); return; }

    const phone = formData.phone.trim();
    if (phone) {
      const digits = phone.replace(/\D/g, "");
      if (!/^[+\d][\d\s().-]*$/.test(phone) || digits.length < 7 || digits.length > 15) {
        setModalError("Enter a valid phone number (7–15 digits)."); return;
      }
    }

    const experienceYears =
      formData.experienceYears === "" ? null : Number(formData.experienceYears);
    if (
      experienceYears !== null &&
      (!Number.isInteger(experienceYears) || experienceYears < 0)
    ) {
      setModalError("Experience must be a non-negative whole number."); return;
    }

    let salary = null;
    if (formData.salary !== "") {
      const parsed = Number(formData.salary);
      if (!Number.isFinite(parsed) || parsed < 0) {
        setModalError("Salary must be a non-negative number."); return;
      }
      salary = parsed;
    }

    // Government ID — optional; validate format when supplied
    const governmentId = formData.governmentId.trim();
    if (governmentId) {
      // Allow 12-digit Aadhaar (spaces/dashes ok), 10-char PAN, or any
      // alphanumeric ID up to 20 chars — matches backend @Pattern
      const stripped = governmentId.replace(/[\s-]/g, "");
      if (!/^[A-Z0-9]+$/i.test(stripped) || stripped.length > 20) {
        setModalError("Government ID must contain only letters, digits, spaces, or dashes (max 20 characters)."); return;
      }
    }

    // ── Build payload ────────────────────────────────────────────────────────
    // employeeCode intentionally omitted — server generates on create,
    // preserves existing value on update.
    const payload = {
      name,
      phone:           phone       || null,
      role:            formData.role.trim() || null,
      experienceYears,
      skills:          formData.skills,
      joiningDate:     formData.joiningDate || null,
      salary,
      // governmentId: send only when the user typed something.
      // Blank → omitted → backend preserves existing encrypted value on edit,
      // stores null on create. The raw value travels over HTTPS and is
      // encrypted server-side immediately; it is never echoed back in responses.
      ...(governmentId ? { governmentId } : {}),
      // Status is only included in the Edit form; on Add it defaults server-side.
      ...(formMechanic.id ? { status: formData.status } : {}),
    };

    setSaving(true);
    setModalError("");
    try {
      if (formMechanic.id) await updateMechanic(formMechanic.id, payload);
      else                 await createMechanic(payload);
      setFormMechanic(null);
      setNotice(formMechanic.id ? "Mechanic updated." : "Mechanic added.");
      await loadData();
    } catch (err) {
      setModalError(err instanceof Error ? err.message : "Mechanic could not be saved.");
    } finally {
      setSaving(false);
    }
  };

  // ── Assign job ──────────────────────────────────────────────────────────────

  const eligibleJobCards = useMemo(
    () =>
      jobCards.filter((job) => {
        const status = String(job.status || "").toUpperCase();
        return ACTIVE_JOB_STATUSES.has(status) && !job.mechanicId;
      }),
    [jobCards],
  );

  const handleAssignJob = async (e) => {
    e.preventDefault();
    if (!assignmentMechanic || !selectedJobCardId || saving) return;
    setSaving(true);
    setModalError("");
    try {
      const job = await getJobCard(selectedJobCardId);
      await updateJobCard(job.id, {
        customerId:        job.customerId,
        vehicleId:         job.vehicleId,
        mechanicId:        assignmentMechanic.id,
        inspectionId:      job.inspectionId      ?? null,
        serviceType:       job.serviceType,
        complaint:         job.complaint         ?? null,
        technicianNotes:   job.technicianNotes   ?? null,
        odometerReading:   job.odometerReading   ?? null,
        estimatedDelivery: job.estimatedDelivery ?? null,
        estimatedCost:     job.estimatedCost     ?? null,
        status:            job.status,
      });
      setAssignmentMechanic(null);
      setSelectedJobCardId("");
      setNotice(`Job ${job.jobCardNumber || `#${job.id}`} assigned to ${assignmentMechanic.name}.`);
      await loadData();
    } catch (err) {
      setModalError(err instanceof Error ? err.message : "Job could not be assigned.");
    } finally {
      setSaving(false);
    }
  };

  // ── Close ───────────────────────────────────────────────────────────────────

  const closeModals = () => {
    if (saving) return;
    setFormMechanic(null);
    setAssignmentMechanic(null);
    setShowGovId(false);
    setModalError("");
  };

  useEffect(() => {
    if (!notice) return undefined;
    const t = window.setTimeout(() => setNotice(""), 5000);
    return () => window.clearTimeout(t);
  }, [notice]);

  useEffect(() => {
    if (formMechanic === null && assignmentMechanic === null) return undefined;
    const onKey = (ev) => { if (ev.key === "Escape" && !saving) closeModals(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [formMechanic, assignmentMechanic, saving]);

  const getAttendanceLabel = (mechanic) => {
    const rec = attendanceByMechanic.get(Number(mechanic.id));
    if (!rec) return "No attendance";
    return rec.open ? "Present" : "Checked out";
  };

  const isEditMode = Boolean(formMechanic?.id);

  // ── Render ──────────────────────────────────────────────────────────────────

  return (
    <main className="garage-mechanics-content">

      {/* ── Page header ─────────────────────────────────────────────────── */}
      <header className="mechanics-heading-row">
        <div>
          <p className="mechanics-eyebrow">GARAGE TEAM</p>
          <h1>MECHANICS</h1>
        </div>
        <button className="add-mechanic-button" onClick={openAddModal} type="button">
          <span aria-hidden="true">+</span> ADD MECHANIC
        </button>
      </header>

      {notice && <div className="mechanics-notice" role="status">{notice}</div>}

      {/* ── Stats bar ───────────────────────────────────────────────────── */}
      <section className="mechanics-stats" aria-label="Mechanic summary">
        <article className="mechanics-stat-card">
          <span className="mechanics-stat-label">TOTAL STAFF</span>
          <strong className="mechanics-stat-value">
            {loading ? "—" : errors.mechanics ? "—" : mechanics.length}
          </strong>
        </article>
        <article className="mechanics-stat-card">
          <span className="mechanics-stat-label">PRESENT TODAY</span>
          <strong className="mechanics-stat-value lime">
            {loading || errors.attendance ? "—" : presentCount}
          </strong>
        </article>
        <article className="mechanics-stat-card">
          <span className="mechanics-stat-label">ON LEAVE</span>
          <strong className="mechanics-stat-value">—</strong>
          <span className="mechanics-stat-note">Not tracked by attendance</span>
        </article>
        <article className="mechanics-stat-card">
          <span className="mechanics-stat-label">AVG RATING</span>
          <strong className="mechanics-stat-value rating-value">
            {loading || errors.performance || averageRating === null
              ? "—"
              : `${averageRating.toFixed(1)}★`}
          </strong>
          <span className="mechanics-stat-note">Rated mechanics with recent job activity</span>
        </article>
      </section>

      {/* ── Data-fetch error notices ─────────────────────────────────────── */}
      {(errors.mechanics || errors.attendance || errors.jobCards || errors.performance) && (
        <aside className="mechanics-data-notice" role="status">
          {errors.mechanics   && <p>{errors.mechanics}</p>}
          {errors.attendance  && <p>Attendance unavailable: {errors.attendance}</p>}
          {errors.jobCards    && <p>Job counts and assignment choices unavailable: {errors.jobCards}</p>}
          {errors.performance && <p>Ratings unavailable: {errors.performance}</p>}
          <button type="button" className="mechanics-retry" onClick={loadData}>RETRY</button>
        </aside>
      )}

      {/* ── Cards grid ──────────────────────────────────────────────────── */}
      {loading ? (
        <div className="mechanics-state" role="status">Loading mechanics…</div>
      ) : errors.mechanics ? (
        <div className="mechanics-state mechanics-error" role="alert">
          Mechanics could not be loaded. Use Retry to try again.
        </div>
      ) : mechanics.length === 0 ? (
        <div className="mechanics-state">
          <strong>No mechanics found</strong>
          <span>Add a mechanic to see your garage team here.</span>
        </div>
      ) : (
        <section className="mechanics-grid" aria-label="Mechanics">
          {mechanics.map((mechanic) => {
            const attendanceRecord = attendanceByMechanic.get(Number(mechanic.id));
            const jobCount  = jobsByMechanic.get(Number(mechanic.id)) || { active: 0, completed: 0 };
            const rating    = ratingsByMechanic.get(Number(mechanic.id));
            const hasRating = rating &&
              getRatingCount(rating) > 0 &&
              rating.averageCustomerRating !== null &&
              rating.averageCustomerRating !== undefined &&
              Number.isFinite(Number(rating.averageCustomerRating));
            const presence  = getAttendanceLabel(mechanic);
            const skillList = Array.isArray(mechanic.skills) ? mechanic.skills : [];

            return (
              <article className="mechanic-card" key={mechanic.id}>
                <div className="mechanic-card-top">
                  <div className="mechanic-main-info">
                    <div className="mechanic-name-row">
                      <span
                        className={`mechanic-status-dot ${attendanceRecord?.open ? "is-present" : ""}`}
                        aria-label={attendanceRecord?.open ? "Present" : "Attendance not open"}
                      />
                      <h2>{mechanic.name}</h2>
                    </div>
                    <p className="mechanic-role">
                      {mechanic.role
                        ? `${mechanic.role}${mechanic.experienceYears != null ? ` · ${mechanic.experienceYears} ${mechanic.experienceYears === 1 ? "year" : "years"}` : ""}`
                        : mechanic.experienceYears == null
                          ? "Mechanic · Experience not recorded"
                          : `Mechanic · ${mechanic.experienceYears} ${mechanic.experienceYears === 1 ? "year" : "years"} experience`}
                    </p>
                    <p className="mechanic-phone">{mechanic.phone || "Phone not provided"}</p>
                  </div>
                  <div className="mechanic-status-info">
                    <span className="mechanic-id">{mechanic.employeeCode || `#${mechanic.id}`}</span>
                    <span
                      className={`mechanic-presence ${attendanceRecord?.open ? "present" : ""}`}
                      title={attendanceRecord ? "Based on today's attendance record" : "No attendance record for today"}
                    >
                      {presence}
                    </span>
                  </div>
                </div>

                <div className="mechanic-skills">
                  {skillList.length > 0
                    ? skillList.map((s) => <span className="mechanic-skill" key={s}>{s}</span>)
                    : <span className="mechanic-skill unavailable-skill">No skills recorded</span>}
                </div>

                <div className="mechanic-divider" />

                <div className="mechanic-card-stats">
                  <div className="mechanic-card-stat">
                    <strong>{errors.jobCards ? "—" : jobCount.active}</strong>
                    <span>Active jobs</span>
                  </div>
                  <div className="mechanic-card-stat">
                    <strong>{errors.jobCards ? "—" : jobCount.completed}</strong>
                    <span>Completed</span>
                  </div>
                  <div className="mechanic-card-stat rating">
                    <strong
                      title={
                        hasRating
                          ? `${rating.ratingCount} customer ratings`
                          : rating ? "No customer ratings recorded" : "Rating not available"
                      }
                    >
                      {hasRating ? `${Number(rating.averageCustomerRating).toFixed(1)}★` : "—"}
                    </strong>
                    <span>Rating</span>
                  </div>
                </div>

                <div className="mechanic-divider" />

                <div className="mechanic-card-footer">
                  <span className="mechanic-employment-status">
                    {mechanic.status || "Status unavailable"}
                  </span>
                  <div className="mechanic-actions">
                    <button type="button" className="mechanic-edit-button" onClick={() => handleEdit(mechanic)}>
                      Edit
                    </button>
                    <button
                      type="button"
                      className="mechanic-assign-button"
                      onClick={() => { setModalError(""); setSelectedJobCardId(""); setAssignmentMechanic(mechanic); }}
                    >
                      Assign Job
                    </button>
                  </div>
                </div>
              </article>
            );
          })}
        </section>
      )}

      {/* ── Add / Edit mechanic modal ────────────────────────────────────── */}
      {formMechanic !== null && (
        <div
          className="mechanic-modal-overlay"
          onMouseDown={(e) => { if (e.target === e.currentTarget) closeModals(); }}
        >
          <section
            className="mechanic-modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="mechanic-form-title"
          >
            {/* ── Header ──────────────────────────────────────────────── */}
            <header className="mechanic-modal-header">
              <h2 id="mechanic-form-title">
                {isEditMode ? "EDIT MECHANIC" : "ADD MECHANIC"}
              </h2>
              <button
                type="button"
                className="mechanic-modal-close"
                onClick={closeModals}
                aria-label="Close dialog"
              >
                ×
              </button>
            </header>

            {/* ── Form body ────────────────────────────────────────────── */}
            <form className="mechanic-form" onSubmit={handleMechanicSave} noValidate>

              {/* Row 1 — Full Name + Phone */}
              <div className="mechanic-form-row">
                <div className="mechanic-form-group">
                  <label htmlFor="mf-name">FULL NAME *</label>
                  <input
                    id="mf-name"
                    name="name"
                    value={formData.name}
                    onChange={handleInputChange}
                    autoComplete="name"
                    maxLength={100}
                  />
                </div>
                <div className="mechanic-form-group">
                  <label htmlFor="mf-phone">PHONE</label>
                  <input
                    id="mf-phone"
                    name="phone"
                    type="tel"
                    value={formData.phone}
                    onChange={handleInputChange}
                    autoComplete="tel"
                    maxLength={30}
                  />
                </div>
              </div>

              {/* Row 2 — Role (full width) */}
              <div className="mechanic-form-group">
                <label htmlFor="mf-role">ROLE</label>
                <select
                  id="mf-role"
                  name="role"
                  value={formData.role}
                  onChange={handleInputChange}
                >
                  <option value="">Select role…</option>
                  {ROLE_OPTIONS.map((r) => (
                    <option key={r} value={r}>{r}</option>
                  ))}
                </select>
              </div>

              {/* Row 3 — Experience (full width) */}
              <div className="mechanic-form-group">
                <label htmlFor="mf-exp">EXPERIENCE (YEARS)</label>
                <input
                  id="mf-exp"
                  name="experienceYears"
                  type="number"
                  min="0"
                  step="1"
                  value={formData.experienceYears}
                  onChange={handleInputChange}
                />
              </div>

              {/* Row 4 — Skills grid */}
              <div className="mechanic-form-group">
                <label>SKILLS (SELECT ALL THAT APPLY)</label>
                <div
                  className="mechanic-skills-grid"
                  role="group"
                  aria-label="Select skills"
                >
                  {SKILL_OPTIONS.map((skill) => (
                    <label key={skill} className="mechanic-skill-checkbox">
                      <input
                        type="checkbox"
                        checked={formData.skills.includes(skill)}
                        onChange={() => handleSkillToggle(skill)}
                      />
                      <span>{skill}</span>
                    </label>
                  ))}
                </div>
              </div>

              {/* Row 5 — Date of Joining + Salary */}
              <div className="mechanic-form-row">
                <div className="mechanic-form-group">
                  <label htmlFor="mf-joining">DATE OF JOINING</label>
                  <input
                    id="mf-joining"
                    name="joiningDate"
                    type="date"
                    value={formData.joiningDate}
                    onChange={handleInputChange}
                  />
                </div>
                <div className="mechanic-form-group">
                  <label htmlFor="mf-salary">SALARY (₹/MONTH)</label>
                  <input
                    id="mf-salary"
                    name="salary"
                    type="number"
                    min="0"
                    step="0.01"
                    placeholder="0.00"
                    value={formData.salary}
                    onChange={handleInputChange}
                  />
                </div>
              </div>

              {/* Government ID — editable. The plaintext typed here is sent once and
                  encrypted server-side; the server never echoes it back, only a mask. */}
              <div className="mechanic-form-group">
                <label htmlFor="mf-govid">GOVERNMENT ID / AADHAAR</label>
                <div className="mechanic-govid-field">
                  <input
                    id="mf-govid"
                    name="governmentId"
                    type={showGovId ? "text" : "password"}
                    value={formData.governmentId}
                    onChange={handleInputChange}
                    autoComplete="off"
                    spellCheck={false}
                    maxLength={24}
                    placeholder={isEditMode ? "Leave blank to keep the stored ID" : "Optional — Aadhaar or PAN"}
                  />
                  <button
                    type="button"
                    className="mechanic-govid-toggle"
                    onClick={() => setShowGovId((prev) => !prev)}
                    aria-pressed={showGovId}
                    aria-label={showGovId ? "Hide Government ID" : "Show Government ID"}
                    tabIndex={0}
                  >
                    {showGovId ? "HIDE" : "SHOW"}
                  </button>
                </div>

                {/* Edit mode: the stored, masked ID is shown separately and is never
                    placed in the input above nor submitted as the actual ID. */}
                {isEditMode && (
                  <p className="mechanic-govid-hint">
                    {formMechanic.maskedGovernmentId ? (
                      <>
                        Stored ID:{" "}
                        <span className="mechanic-govid-masked">{formMechanic.maskedGovernmentId}</span>
                        {" — leave blank to keep it unchanged."}
                      </>
                    ) : (
                      "No Government ID on record — type one to store it."
                    )}
                  </p>
                )}
              </div>

              {/* Row 6 — Status (Edit only) */}
              {isEditMode && (
                <div className="mechanic-form-group mechanic-form-group--compact">
                  <label htmlFor="mf-status">EMPLOYMENT STATUS</label>
                  <select
                    id="mf-status"
                    name="status"
                    value={formData.status}
                    onChange={handleInputChange}
                  >
                    <option value="ACTIVE">ACTIVE</option>
                    <option value="INACTIVE">INACTIVE</option>
                  </select>
                </div>
              )}

              {/* Read-only Employee ID (Edit only) */}
              {isEditMode && formMechanic.employeeCode && (
                <div className="mechanic-form-group">
                  <label>EMPLOYEE ID</label>
                  <div className="mechanic-employee-id-display">
                    {formMechanic.employeeCode}
                  </div>
                </div>
              )}

              {/* Error */}
              {modalError && (
                <p className="mechanic-form-error" role="alert">{modalError}</p>
              )}

              {/* ── Footer ─────────────────────────────────────────────── */}
              <footer className="mechanic-modal-footer">
                <button
                  type="submit"
                  className="mechanic-submit-button"
                  disabled={saving}
                >
                  {saving ? "SAVING…" : isEditMode ? "SAVE CHANGES" : "ADD MECHANIC"}
                </button>
                <button
                  type="button"
                  className="mechanic-cancel-button"
                  onClick={closeModals}
                  disabled={saving}
                >
                  Cancel
                </button>
              </footer>
            </form>
          </section>
        </div>
      )}

      {/* ── Assign job modal ─────────────────────────────────────────────── */}
      {assignmentMechanic && (
        <div
          className="mechanic-modal-overlay"
          onMouseDown={(e) => { if (e.target === e.currentTarget) closeModals(); }}
        >
          <section
            className="mechanic-modal assignment-modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="assignment-title"
          >
            <header className="mechanic-modal-header">
              <h2 id="assignment-title">ASSIGN JOB</h2>
              <button
                type="button"
                className="mechanic-modal-close"
                onClick={closeModals}
                aria-label="Close dialog"
              >
                ×
              </button>
            </header>

            <form className="mechanic-form" onSubmit={handleAssignJob}>
              <p className="assignment-description">
                Select an active, unassigned job for {assignmentMechanic.name}.
              </p>

              {errors.jobCards ? (
                <p className="mechanic-form-error" role="alert">Job-card choices are unavailable.</p>
              ) : eligibleJobCards.length === 0 ? (
                <div className="assignment-empty">There are no unassigned active job cards.</div>
              ) : (
                <div className="mechanic-form-group">
                  <label htmlFor="assignment-job-card">ACTIVE JOB CARD</label>
                  <select
                    id="assignment-job-card"
                    value={selectedJobCardId}
                    onChange={(e) => setSelectedJobCardId(e.target.value)}
                    required
                  >
                    <option value="">Choose a job card</option>
                    {eligibleJobCards.map((job) => (
                      <option value={job.id} key={job.id}>
                        {job.jobCardNumber || `#${job.id}`} · {job.customerName || "Customer unavailable"} · {job.serviceType || "Service unavailable"}
                      </option>
                    ))}
                  </select>
                </div>
              )}

              {modalError && (
                <p className="mechanic-form-error" role="alert">{modalError}</p>
              )}

              <footer className="mechanic-modal-footer">
                <button
                  type="submit"
                  className="mechanic-submit-button"
                  disabled={saving || !selectedJobCardId || eligibleJobCards.length === 0 || Boolean(errors.jobCards)}
                >
                  {saving ? "ASSIGNING…" : "ASSIGN JOB"}
                </button>
                <button
                  type="button"
                  className="mechanic-cancel-button"
                  onClick={closeModals}
                  disabled={saving}
                >
                  Cancel
                </button>
              </footer>
            </form>
          </section>
        </div>
      )}

    </main>
  );
}

export default GarageOwnerMechanics;
