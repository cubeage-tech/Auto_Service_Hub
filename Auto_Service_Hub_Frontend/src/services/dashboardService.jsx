import { fetchWithAuth } from "./api";

async function getData(path) {
  const response = await fetchWithAuth(`/api/v1${path}`);

  let body;
  try {
    body = await response.json();
  } catch {
    if (!response.ok) {
      throw new Error(`Dashboard API request failed (${response.status}).`);
    }
    if (response.status !== 204) {
      throw new Error(`Dashboard API returned invalid JSON (${response.status}).`);
    }
  }

  if (!response.ok) {
    const message =
      body?.message || `Dashboard API request failed (${response.status}).`;
    if (response.status === 401 || response.status === 403) {
      throw new Error(`${message} Sign in with an authorized account and try again.`);
    }
    throw new Error(message);
  }

  if (!body?.success) {
    throw new Error(body?.message || "Dashboard API returned an unsuccessful response.");
  }

  return body.data;
}

export const getDashboardSummary = () =>
  getData("/dashboard/summary");

export const getRecentJobCards = () =>
  getData("/job-cards?page=0&size=6&sort=createdAt,desc").then((page) => {
    if (Array.isArray(page)) {
      return page;
    }
    if (Array.isArray(page?.content)) {
      return page.content;
    }
    throw new Error("Job cards API returned an unexpected page format.");
  });

async function getAllPages(path, sort) {
  const records = [];
  const size = 100;
  for (let pageNumber = 0; ; pageNumber += 1) {
    const separator = path.includes("?") ? "&" : "?";
    const page = await getData(
      `${path}${separator}page=${pageNumber}&size=${size}&sort=${encodeURIComponent(sort)}`
    );
    if (Array.isArray(page)) {
      return page;
    }
    if (!Array.isArray(page?.content)) {
      throw new Error("Dashboard list API returned an unexpected page format.");
    }
    records.push(...page.content);
    if (pageNumber + 1 >= (page.totalPages ?? 1) || page.content.length === 0) {
      return records;
    }
  }
}

export const getDashboardJobCards = () =>
  getAllPages("/job-cards", "createdAt,desc");

export const getInvoices = () =>
  getAllPages("/invoices", "invoiceDate,desc");

export const getMechanics = () =>
  getAllPages("/mechanics", "name,asc");

export const getAiInsights = (from, to) =>
  getData(
    `/reports/ai-insights?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`
  ).then((report) => {
    if (!Array.isArray(report?.insights)) {
      throw new Error("AI insights API returned an unexpected report format.");
    }
    return report.insights;
  });

export const getRevenueReport = (from, to) =>
  getData(
    `/reports/revenue?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`
  ).then((report) => {
    if (!Array.isArray(report?.dailyTrend)) {
      throw new Error("Revenue API returned an unexpected report format.");
    }
    return report;
  });
