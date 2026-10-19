"use client";
import { useEffect, useState, useCallback, useRef } from "react";
import {
  onAuthStateChanged,
  signInWithPopup,
  GoogleAuthProvider,
  signOut,
  User,
} from "firebase/auth";
import { collection, onSnapshot, doc } from "firebase/firestore";
import {
  Phone,
  LayoutDashboard,
  Users,
  Target,
  CalendarClock,
  History,
  LogOut,
  ArrowRight,
  Check,
  RefreshCw,
  Menu,
  Building2,
  Search,
} from "lucide-react";
import { firebase, api, Row } from "../lib/firebase";
import { PhoneRequest } from "./phone-request";
import { ImportLeads } from "./import-leads";
import { watchRows } from "../lib/spark";
import { Reports } from "./reports";
const reportTabs = ['dashboard', 'callHistory', 'reports', 'targetDefinitions'];
type Company = { id: string; name: string; role: string; status: string };
const labels: Record<string, string> = {
  dashboard: "Dashboard",
  callHistory: "Call history",
  reports: "Call reports",
  leads: "My leads",
  campaigns: "Campaigns",
  targetDefinitions: "Targets",
  followUps: "Follow-ups",
  crmCallAttempts: "Activity history",
};
const value = (r: Row, k: string) => String(r[k] ?? "—");
const date = (n: unknown) =>
  typeof n === "number" ? new Date(n).toLocaleString() : "—";
export default function Page() {
  const [user, setUser] = useState<User | null>(null),
    [ready, setReady] = useState(false),
    [companies, setCompanies] = useState<Company[]>([]),
    [company, setCompany] = useState(""),
    [role, setRole] = useState(""),
    [error, setError] = useState(""),
    [tab, setTab] = useState("dashboard"),
    [rows, setRows] = useState<Row[]>([]),
    [next, setNext] = useState<string | null>(null),
    [busy, setBusy] = useState(false),
    [online, setOnline] = useState(true),
    [search, setSearch] = useState(""),
    [selected, setSelected] = useState<Row | null>(null),
    [session, setSession] = useState(""),
    [statuses, setStatuses] = useState<Row[]>([]),
    [status, setStatus] = useState(""),
    [remarks, setRemarks] = useState(""),
    [followup, setFollowup] = useState(""),
    [notice, setNotice] = useState("");
  const generation = useRef(0);
  const admin = ["owner", "admin"].includes(role);
  useEffect(() => {
    let stop = () => {};
    try {
      stop = onAuthStateChanged(firebase().auth, (u) => {
        generation.current++;
        setUser(u);
        setReady(true);
        setCompany("");
        setRole("");
        setSelected(null);
        setRows([]);
      });
    } catch (e) {
      setError(String(e));
      setReady(true);
    }
    return stop;
  }, []);
  useEffect(() => {
    const update = () => setOnline(navigator.onLine);
    update();
    window.addEventListener("online", update);
    window.addEventListener("offline", update);
    return () => {
      window.removeEventListener("online", update);
      window.removeEventListener("offline", update);
    };
  }, []);
  useEffect(() => {
    if (!user) {
      setCompanies([]);
      return;
    }
    return onSnapshot(
      collection(firebase().db, "users", user.uid, "companies"),
      (s) =>
        setCompanies(s.docs.map((d) => ({ ...d.data(), id: d.id }) as Company)),
      (e) => setError(`${e.code}: ${e.message}`),
    );
  }, [user]);
  useEffect(() => {
    if (!company && companies.some((c) => c.status === "approved"))
      setCompany(companies.find((c) => c.status === "approved")!.id);
  }, [companies, company]);
  useEffect(() => {
    if (!company || !user) return;
    return onSnapshot(
      doc(firebase().db, "companies", company, "members", user.uid),
      (s) => {
        if (!s.data()?.approved) {
          generation.current++;
          setRole("");
          setRows([]);
          setSelected(null);
          setError(
            "MEMBERSHIP_REQUIRED: Your company access is no longer active.",
          );
        } else setRole(s.data()!.role);
      },
      (e) => {
        setRole("");
        setRows([]);
        setError(`${e.code}: ${e.message}`);
      },
    );
  }, [company, user]);
  const load = useCallback(
    async (after = "") => {
      if (!company || !role || reportTabs.includes(tab)) return;
      const gen = generation.current;
      setBusy(true);
      try {
        const result = await api(
          company,
          `${tab}?limit=50${after ? `&after=${encodeURIComponent(after)}` : ""}`,
        );
        if (gen !== generation.current) return;
        setRows((old) => (after ? [...old, ...result.rows] : result.rows));
        setNext(result.next);
        setError("");
      } catch (e) {
        setError(String(e));
      } finally {
        setBusy(false);
      }
    },
    [company, role, tab],
  );
  useEffect(() => {
    generation.current++;
    setRows([]);
    setSelected(null);
    setSearch("");
    if (!company || !role || !user || reportTabs.includes(tab)) return;
    return watchRows(company, tab, user.uid, admin, (items, cursor) => {
      setRows(items); setNext(cursor); setError("");
    }, e => { setRows([]); setError(String(e)); });
  }, [company, role, tab, user, admin]);
  async function action(fn: () => Promise<void>) {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      await fn();
    } catch (e) {
      setError(String(e));
    } finally {
      setBusy(false);
    }
  }
  async function start(lead: Row) {
    const gen = generation.current;
    await action(async () => {
      const data = await api(company, "claim", { leadId: lead.id });
      const outcomeRows = (await api(company, "dispositionStatuses")).rows;
      if (gen !== generation.current) return;
      setSelected(data.lead);
      setSession(data.sessionId);
      setStatuses(outcomeRows);
      setRemarks("");
      setStatus("");
      setFollowup("");
    });
  }
  async function save() {
    if (!selected) return;
    const gen = generation.current;
    await action(async () => {
      const body = {
        leadId: selected.id,
        sessionId: session,
        version: selected.version,
        dispositionId: status,
        remarks,
        tags: [],
        nextFollowUpAt: followup ? new Date(followup).getTime() : null,
      };
      // Persist the operation key for this exact payload so a timeout can be retried safely.
      const storageKey = `crm-outcome:${company}:${user?.uid}:${session}`,
        fingerprint = JSON.stringify(body);
      const prior = JSON.parse(sessionStorage.getItem(storageKey) || "null");
      const key =
        prior?.fingerprint === fingerprint ? prior.key : crypto.randomUUID();
      sessionStorage.setItem(storageKey, JSON.stringify({ key, fingerprint }));
      await api(company, "dispositions", body, key);
      sessionStorage.removeItem(storageKey);
      if (gen !== generation.current) return;
      setSelected(null);
      setNotice("Outcome saved. Phone-call verification remains separate.");
      const result = await api(company, "leads?limit=50");
      if (gen !== generation.current) return;
      setRows(result.rows);
      setNext(result.next);
      const nextLead = result.rows.find(
        (r: Row) => r.id !== selected.id && !r.terminal && !r.doNotCall,
      );
      if (nextLead) {
        const claim = await api(company, "claim", { leadId: nextLead.id });
        if (gen !== generation.current) return;
        setSelected(claim.lead);
        setSession(claim.sessionId);
        setStatus("");
        setRemarks("");
        setFollowup("");
      }
    });
  }
  async function createCampaign(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget,
      data = new FormData(form);
    await action(async () => {
      await api(company, "campaigns", {
        name: data.get("name"),
        description: data.get("description"),
        status: "draft",
      });
      form.reset();
      await load();
    });
  }
  if (!ready)
    return (
      <main className="login">
        <p>Opening your workspace…</p>
      </main>
    );
  if (!user)
    return (
      <main className="login">
        <div className="login-card">
          <div className="brand">
            <span className="logo">
              <Phone size={22} />
            </span>{" "}
            CallTrack <b>Pro</b>
          </div>
          <span className="eyebrow">THE CONNECTED CALLING WORKSPACE</span>
          <h1>
            Better conversations.
            <br />
            Clearer results.
          </h1>
          <p>
            Access your company’s leads, follow-ups and performance with your
            existing CallTrack account.
          </p>
          <button
            onClick={() =>
              action(async () => {
                await signInWithPopup(
                  firebase().auth,
                  new GoogleAuthProvider(),
                );
              })
            }
            disabled={busy}
          >
            Continue with Google <ArrowRight size={18} />
          </button>
          {error && (
            <div role="alert" className="error">
              {error}
            </div>
          )}
          <small>Use the same Google account as your Android app.</small>
        </div>
        <div className="login-art">
          <div className="orbit" />
          <Phone size={92} />
          <h2>
            Your phone. Your team.
            <br />
            One shared workspace.
          </h2>
          <p>Call monitoring and CRM, connected.</p>
        </div>
      </main>
    );
  return (
    <div className="shell">
      <aside>
        <div className="brand">
          <span className="logo">
            <Phone size={20} />
          </span>
          CallTrack <b>Pro</b>
        </div>
        <div className="workspace-label">WORKSPACE</div>
        <nav>
          {Object.entries(labels)
            .filter(([key]) => admin || key !== "campaigns")
            .map(([key, label], i) => {
              const Icon = key === 'dashboard' ? LayoutDashboard : key === 'targetDefinitions' ? Target : key === 'campaigns' ? Building2 : key === 'followUps' ? CalendarClock : key === 'leads' ? Users : History;
              return (
                <button
                  key={key}
                  className={tab === key ? "active" : ""}
                  onClick={() => {
                    generation.current++;
                    setTab(key);
                  }}
                >
                  <Icon size={19} />
                  {key === "leads" && admin ? "Leads & customers" : label}
                </button>
              );
            })}
        </nav>
        <div className="sidebar-bottom">
          <span className="avatar">{user.displayName?.slice(0, 1) || "U"}</span>
          <div>
            <strong>{user.displayName || "Your account"}</strong>
            <small>{role || "No active company"}</small>
          </div>
          <button
            aria-label="Sign out"
            onClick={() => signOut(firebase().auth)}
          >
            <LogOut size={18} />
          </button>
        </div>
      </aside>
      <div className="main">
        <header>
          <div>
            <span className="eyebrow">COMPANY WORKSPACE</span>
            <select
              aria-label="Current company"
              value={company}
              onChange={(e) => {
                generation.current++;
                setRows([]);
                setSelected(null);
                setRole("");
                setCompany(e.target.value);
              }}
            >
              {companies
                .filter((c) => c.status === "approved")
                .map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
            </select>
          </div>
          <span className={online ? "connection" : "offline"}>
            <i />
            {online ? "Online" : "Offline — changes unavailable"}
          </span>
        </header>
        <main className="content">
          {!company ? (
            <section className="empty">
              <Building2 />
              <h1>
                {companies.some((c) => c.status === "pending")
                  ? "Approval pending"
                  : "Welcome to CallTrack"}
              </h1>
              <p>
                Join or create a company in your Android app. Your approved
                membership will appear here automatically.
              </p>
            </section>
          ) : (
            <>
              <div className="title-row">
                <div>
                  <span className="eyebrow">
                    {admin ? "TEAM OPERATIONS" : "YOUR DAILY WORKSPACE"}
                  </span>
                  <h1>
                    {tab === "leads" && admin
                      ? "Leads & customers"
                      : labels[tab]}
                  </h1>
                  <p>
                    {tab === "leads"
                      ? "Every customer, every conversation, one clear next step."
                      : "Keep your team’s work connected and up to date."}
                  </p>
                </div>
                {!reportTabs.includes(tab) && <button
                  className="secondary"
                  disabled={busy || !online}
                  onClick={() => load()}
                >
                  <RefreshCw size={16} />
                  Refresh
                </button>}
              </div>
              {error && (
                <div role="alert" className="error">
                  {error}
                </div>
              )}
              {notice && (
                <div role="status" className="notice">
                  <Check size={17} />
                  {notice}
                </div>
              )}
              {admin && tab === "campaigns" && (
                <form
                  className="form-card inline-form"
                  onSubmit={createCampaign}
                >
                  <label>
                    Campaign name
                    <input
                      name="name"
                      required
                      maxLength={160}
                      placeholder="Give your campaign a name"
                    />
                  </label>
                  <label>
                    Description
                    <input
                      name="description"
                      maxLength={1000}
                      placeholder="Purpose and audience"
                    />
                  </label>
                  <button disabled={busy || !online}>Create campaign</button>
                </form>
              )}
            {admin && tab === "leads" && <ImportLeads key={company} company={company} onComplete={() => void load()} />}
            {admin && tab === "leads" && (
                <div className="callout">
                  <div>
                    <strong>Prepare your calling workspace</strong>
                    <p>
                      Initialize the default outcome buttons once for this
                      company.
                    </p>
                  </div>
                  <button
                    className="secondary"
                    disabled={busy || !online}
                    onClick={() =>
                      action(async () => {
                        await api(company, "initialize", {});
                        setNotice(
                          "Default outcomes are ready. Existing outcomes were preserved.",
                        );
                      })
                    }
                  >
                    Initialize outcomes
                  </button>
                </div>
              )}
              {reportTabs.includes(tab) && role && <Reports key={`${company}:${user.uid}:${role}`} company={company} uid={user.uid} admin={admin} view={tab} />}
              {!reportTabs.includes(tab) && <section className="table-card">
                <div className="table-toolbar">
                  <strong>
                    {labels[tab]}{" "}
                    <span className="count">{rows.length} loaded</span>
                  </strong>
                  <label className="search">
                    <Search size={16} />
                    <input
                      aria-label="Search loaded records"
                      placeholder="Search loaded records…"
                      value={search}
                      onChange={(e) => setSearch(e.target.value)}
                    />
                  </label>
                </div>
                {busy && !rows.length ? (
                  <div className="empty">Loading company records…</div>
                ) : !rows.length ? (
                  <div className="empty">
                    <Users size={30} />
                    <h2>No records yet</h2>
                    <p>Records saved to this company will appear here.</p>
                  </div>
                ) : (
                  <div className="table-scroll">
                    <table>
                      <thead>
                        <tr>
                          <th>{tab === "leads" ? "Customer" : "Record"}</th>
                          <th>{tab === "leads" ? "Phone" : "Details"}</th>
                          <th>Status</th>
                          <th>Updated</th>
                          {tab === "leads" && <th>Action</th>}
                        </tr>
                      </thead>
                      <tbody>
                        {rows
                          .filter((r) =>
                            JSON.stringify(r)
                              .toLowerCase()
                              .includes(search.toLowerCase()),
                          )
                          .map((r) => (
                            <tr key={r.id}>
                              <td>
                                <strong>
                                  {value(
                                    r,
                                    tab === "leads"
                                      ? "customerName"
                                      : tab === "campaigns" ||
                                          tab === "targetDefinitions"
                                        ? "name"
                                        : "leadId",
                                  )}
                                </strong>
                                {tab === "leads" && (
                                  <small>{value(r, "companyName")}</small>
                                )}
                              </td>
                              <td>
                                {tab === "leads"
                                  ? value(r, "primaryPhone")
                                  : tab === "targetDefinitions"
                                    ? `${value(r, "value")} ${value(r, "metric")} / ${value(r, "period")}`
                                    : value(
                                        r,
                                        tab === "crmCallAttempts"
                                          ? "dispositionId"
                                          : "remarks",
                                      )}
                              </td>
                              <td>
                                <span className="badge">
                                  {value(
                                    r,
                                    tab === "crmCallAttempts"
                                      ? "verificationStatus"
                                      : "status",
                                  )}
                                </span>
                              </td>
                              <td>
                                {date(r.updatedAt || r.createdAt || r.dueAt)}
                              </td>
                              {tab === "leads" && (
                                <td>
                                  <button
                                    className="text-button"
                                    disabled={
                                      busy ||
                                      !online ||
                                      !!r.terminal ||
                                      !!r.doNotCall
                                    }
                                    onClick={() => start(r)}
                                  >
                                    Open workspace <ArrowRight size={14} />
                                  </button>
                                </td>
                              )}
                            </tr>
                          ))}
                      </tbody>
                    </table>
                  </div>
                )}
                {next && (
                  <button
                    className="load-more secondary"
                    disabled={busy}
                    onClick={() => load(next)}
                  >
                    Load more
                  </button>
                )}
              </section>}
            </>
          )}
        </main>
      </div>
      {selected && (
        <div className="modal-backdrop">
          <section
            className="caller"
            role="dialog"
            aria-modal="true"
            aria-labelledby="caller-title"
          >
            <div className="caller-top">
              <span className="eyebrow">CALLING WORKSPACE</span>
              <button className="secondary" onClick={() => setSelected(null)}>
                Close
              </button>
            </div>
            <h1 id="caller-title">{value(selected, "customerName")}</h1>
            <p>
              {value(selected, "companyName")} ·{" "}
              {value(selected, "primaryPhone")}
            </p>
            <div className="callout">
              <Phone size={22} />
              <div>
                <PhoneRequest company={company} lead={selected.id} />
              </div>
            </div>
            <h3>Record the outcome</h3>
            <div className="outcomes">
              {statuses
                .filter((s) => s.active)
                .map((s) => (
                  <button
                    key={s.id}
                    className={status === s.id ? "" : "secondary"}
                    onClick={() => setStatus(s.id)}
                  >
                    {value(s, "label")}
                  </button>
                ))}
            </div>
            <label>
              Remarks
              <textarea
                value={remarks}
                onChange={(e) => setRemarks(e.target.value)}
                maxLength={1000}
                placeholder="What should the next conversation pick up from?"
              />
            </label>
            <label>
              Follow-up date and time
              <input
                type="datetime-local"
                value={followup}
                onChange={(e) => setFollowup(e.target.value)}
              />
            </label>
            {error && (
              <div className="error" role="alert">
                {error}
              </div>
            )}
            <div className="caller-bottom">
              <small>Manual outcomes are saved as unverified.</small>
              <button disabled={!status || busy || !online} onClick={save}>
                {busy ? "Saving…" : "Save & next"}
                <ArrowRight size={16} />
              </button>
            </div>
          </section>
        </div>
      )}
    </div>
  );
}
