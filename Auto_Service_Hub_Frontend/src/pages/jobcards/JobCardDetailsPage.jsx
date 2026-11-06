import React, { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { jobCardsApi } from "../../services/resources";
import { getErrorMessage } from "../../services/api";
import "./JobCardDetailsPage.css";

export default function JobCardDetailsPage() {
  const { jobId } = useParams();
  const [job, setJob] = useState(null);
  const [tasks, setTasks] = useState([]);
  const [form, setForm] = useState({ description: "", labourCost: "0", workNotes: "" });
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    Promise.all([jobCardsApi.get(jobId), jobCardsApi.tasks(jobId)])
      .then(([jobRecord, taskRecords]) => {
        setJob(jobRecord);
        setTasks(taskRecords);
      })
      .catch((requestError) => setError(getErrorMessage(requestError)))
      .finally(() => setLoading(false));
  }, [jobId]);

  const addTask = async (event) => {
    event.preventDefault();
    setSaving(true);
    setError("");
    try {
      const created = await jobCardsApi.createTask(jobId, {
        description: form.description.trim(),
        labourCost: Number(form.labourCost || 0),
        workNotes: form.workNotes.trim() || null,
        status: "PENDING",
      });
      setTasks((current) => [...current, created]);
      setForm({ description: "", labourCost: "0", workNotes: "" });
    } catch (requestError) {
      setError(getErrorMessage(requestError));
    } finally {
      setSaving(false);
    }
  };

  if (loading) return <main className="api-data-page"><p role="status">Loading job card…</p></main>;
  if (error && !job) return <main className="api-data-page"><div className="api-data-error" role="alert">{error}</div><Link to="/job-cards">Back to job cards</Link></main>;
  if (!job) return <main className="api-data-page"><p>Job card not found.</p><Link to="/job-cards">Back to job cards</Link></main>;

  return (
    <main className="api-data-page">
      <header className="api-data-header"><div><p>JOB CARD / {job.jobCardNumber}</p><h1>{job.customerName} — {job.serviceType}</h1></div><Link to="/job-cards">Back to job cards</Link></header>
      {error && <div className="api-data-error" role="alert">{error}</div>}
      <section className="api-data-kpis">
        <div className="api-data-kpi"><span>VEHICLE</span><strong>{job.vehicleInfo || "—"}</strong></div>
        <div className="api-data-kpi"><span>MECHANIC</span><strong>{job.mechanicName || "Unassigned"}</strong></div>
        <div className="api-data-kpi"><span>STATUS</span><strong>{job.status}</strong></div>
        <div className="api-data-kpi"><span>ESTIMATED COST</span><strong>{job.estimatedCost == null ? "—" : `₹${Number(job.estimatedCost).toLocaleString("en-IN")}`}</strong></div>
      </section>
      <section className="api-data-card">
        <h2>Job information</h2>
        <p><strong>Complaint:</strong> {job.complaint || "—"}</p>
        <p><strong>Technician notes:</strong> {job.technicianNotes || "—"}</p>
        <p><strong>Estimated delivery:</strong> {job.estimatedDelivery ? new Date(job.estimatedDelivery).toLocaleString() : "—"}</p>
      </section>
      <section className="api-data-card">
        <h2>Repair tasks</h2>
        <div className="api-data-table-wrap"><table className="api-data-table">
          <thead><tr><th>Task</th><th>Mechanic</th><th>Status</th><th>Labour cost</th><th>Work notes</th></tr></thead>
          <tbody>{tasks.map((task) => <tr key={task.id}>
            <td>{task.description}</td><td>{task.mechanicName || "Unassigned"}</td><td>{task.status}</td>
            <td>₹{Number(task.labourCost || 0).toLocaleString("en-IN")}</td><td>{task.workNotes || "—"}</td>
          </tr>)}</tbody>
        </table>{!tasks.length && <p>No tasks recorded.</p>}</div>
      </section>
      <section className="api-data-card">
        <h2>Add a repair task</h2>
        <form className="api-data-form" onSubmit={addTask}>
          <label>Description<input required value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} /></label>
          <label>Labour cost<input type="number" min="0" step="0.01" value={form.labourCost} onChange={(e) => setForm({ ...form, labourCost: e.target.value })} /></label>
          <label>Work notes<input value={form.workNotes} onChange={(e) => setForm({ ...form, workNotes: e.target.value })} /></label>
          <button disabled={saving}>{saving ? "Saving…" : "Add task"}</button>
        </form>
      </section>
    </main>
  );
}
