/* MailHub 前端逻辑：hash 路由单页应用 */
"use strict";

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

let META = null;              // /api/me 返回的 providers / categories
let inboxState = { category: "", account: 0, unread: -1, q: "", page: 1, selected: new Set() };
let otpTimer = null, statusTimer = null;
let charts = [];

/* ---------- 基础设施 ---------- */

async function api(path, opts = {}) {
  const res = await fetch("/api" + path, {
    headers: { "Content-Type": "application/json" },
    credentials: "same-origin",
    ...opts,
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  });
  if (res.status === 401) { showLogin(); throw new Error("未登录"); }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = new Error(data.detail || `请求失败 (${res.status})`);
    err.status = res.status;
    err.protected = res.headers.get("X-MailHub-Protected") === "1";
    throw err;
  }
  return data;
}

// 点击只读输入框时全选内容（替代内联 onclick="this.select()"）
document.addEventListener("click", ev => {
  if (ev.target instanceof HTMLInputElement && ev.target.classList.contains("select-on-click")) {
    ev.target.select();
  }
});

function toast(msg, isErr = false) {
  const t = $("#toast");
  t.textContent = msg;
  t.className = "toast" + (isErr ? " err" : "");
  clearTimeout(t._h);
  t._h = setTimeout(() => t.classList.add("hidden"), 2600);
}

function esc(s) {
  return String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

function fmtTime(ts) {
  if (!ts) return "";
  const d = new Date(ts * 1000), now = new Date();
  const sameDay = d.toDateString() === now.toDateString();
  const pad = n => String(n).padStart(2, "0");
  if (sameDay) return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  if (d.getFullYear() === now.getFullYear()) return `${d.getMonth() + 1}/${d.getDate()}`;
  return `${d.getFullYear()}/${d.getMonth() + 1}/${d.getDate()}`;
}

function ago(ts) {
  const s = Math.max(0, Date.now() / 1000 - ts);
  if (s < 60) return `${s | 0} 秒前`;
  if (s < 3600) return `${(s / 60) | 0} 分钟前`;
  if (s < 86400) return `${(s / 3600) | 0} 小时前`;
  return `${(s / 86400) | 0} 天前`;
}

const MINISTRY_ORDER = ["吏部", "户部", "礼部", "兵部", "刑部", "工部"];

function governanceCardHtml(status) {
  const last = status?.last_run;
  const counts = last?.ministry_counts || {};
  const finished = last?.finished_ts || last?.started_ts || 0;
  const state = status?.running ? "自动裁决进行中"
    : last?.status === "failed" ? "上次运行失败"
    : last ? `上次完成于 ${ago(finished)}` : "尚未运行";
  return `
    <div class="card governance-card">
      <div class="governance-main">
        <div class="governance-title">
          <span class="governance-seal">三省</span>
          <div><h3>${ico("sparkle")} 三省六部自动分拣</h3>
            <p>中书提案 · 门下自动安全裁决 · 尚书落地标签；无需逐封确认，不执行删除、转发或回复。</p>
          </div>
        </div>
        <button class="btn primary" data-governance-run ${status?.running ? "disabled" : ""}>
          ${status?.running ? "分拣进行中…" : "一键自动分拣"}
        </button>
      </div>
      <div class="governance-stats">
        <span><b>${status?.pending || 0}</b> 待治理</span>
        <span><b>${last?.processed_count || 0}</b> 上次处理</span>
        <span><b>${last?.fallback_count || 0}</b> 自动回退</span>
        <span><b>${last?.suspicious_count || 0}</b> 标记可疑</span>
        <span class="governance-state">${esc(state)}</span>
      </div>
      <div class="ministry-strip">
        ${MINISTRY_ORDER.map(m => `<span><b>${m}</b> ${counts[m] || 0}</span>`).join("")}
      </div>
      ${last?.status === "failed" && last?.error ? `<div class="governance-error">${esc(last.error)}</div>` : ""}
    </div>`;
}

/* 一键分拣：只处理待分拣邮件；没有待分拣时询问是否重新分拣最近 7 天 */
const RESORT_DAYS = 7;
function governanceSummary(run) {
  const parts = [`处理 ${run.processed_count || 0} 封`];
  if (run.ai_count) parts.push(`AI 判定 ${run.ai_count} 封`);
  if (run.fallback_count) parts.push(`规则兜底 ${run.fallback_count} 封`);
  return parts.join("，");
}
async function runGovernanceFlow() {
  let run = (await api("/governance/run", { method: "POST" })).last_run || {};
  if ((run.selected_count || 0) > 0) {
    toast(`自动分拣完成：${governanceSummary(run)}`);
    return true;
  }
  if (!confirm(`没有待分拣的邮件：新邮件在同步时已经自动分拣过了。\n\n要按当前的规则和 AI 重新分拣最近 ${RESORT_DAYS} 天的邮件吗？（会调用 AI 接口，邮件较多时需要一些时间）`)) {
    toast("没有待分拣的邮件");
    return false;
  }
  toast(`正在重新分拣最近 ${RESORT_DAYS} 天的邮件…`);
  run = (await api("/governance/run", { method: "POST", body: { days: RESORT_DAYS } })).last_run || {};
  toast(`重新分拣完成：${governanceSummary(run)}`);
  return true;
}

function bindGovernanceRun(root, refresh) {
  const button = $("[data-governance-run]", root);
  if (!button) return;
  button.addEventListener("click", async () => {
    button.disabled = true;
    button.textContent = "三省会审中…";
    try {
      await runGovernanceFlow();
      await refresh();
      refreshBadge();
    } catch (e) {
      toast(e.message, true);
      button.disabled = false;
      button.textContent = "一键自动分拣";
    }
  });
}

function copyText(text) {
  (navigator.clipboard?.writeText(text) || Promise.reject()).then(
    () => toast(`已复制: ${text}`),
    () => {
      const ta = document.createElement("textarea");
      ta.value = text; document.body.appendChild(ta); ta.select();
      document.execCommand("copy"); ta.remove(); toast(`已复制: ${text}`);
    });
}

/* 内联描边图标：统一 24 格线稿，卡片标题与统计卡共用 */
const ICONS = {
  sparkle: `<path d="M12 3.2 13.6 8 18.4 9.6 13.6 11.2 12 16 10.4 11.2 5.6 9.6 10.4 8Z"/><path d="M18.5 15.5 19.2 17.6 21.3 18.3 19.2 19 18.5 21.1 17.8 19 15.7 18.3 17.8 17.6Z"/>`,
  plug: `<path d="M9 3v5"/><path d="M15 3v5"/><path d="M6.5 8h11v3.2a5.5 5.5 0 0 1-11 0Z"/><path d="M12 16.7V21"/>`,
  bell: `<path d="M18 9a6 6 0 1 0-12 0c0 5-2 6.5-2 6.5h16S18 14 18 9Z"/><path d="M13.7 19a2 2 0 0 1-3.4 0"/>`,
  broom: `<path d="M15.5 3.5 20 8"/><path d="M9.5 12 12 9.5l2.5 2.5"/><path d="m4 20 4.2-1.4a3 3 0 0 0 1.9-1.9L11.5 12l4 4-4.8 1.4a3 3 0 0 0-1.9 1.9Z"/>`,
  key: `<circle cx="8" cy="12" r="4.2"/><path d="M12.2 12H21"/><path d="M17.5 12v3.4"/><path d="M20.4 12v2.4"/>`,
  shield: `<path d="M12 3 5 6v5.4c0 4.4 3 8.2 7 9.6 4-1.4 7-5.2 7-9.6V6Z"/>`,
  ruler: `<path d="m4.5 15.5 11-11a2 2 0 0 1 2.8 0l1.2 1.2a2 2 0 0 1 0 2.8l-11 11a2 2 0 0 1-2.8 0l-1.2-1.2a2 2 0 0 1 0-2.8Z"/><path d="m9 11 1.6 1.6"/><path d="m12 8 1.6 1.6"/><path d="m6 14 1.6 1.6"/>`,
  chart: `<path d="M4 20V10"/><path d="M10 20V4"/><path d="M16 20v-7"/><path d="M21 20H3"/>`,
  pie: `<path d="M12 3a9 9 0 1 0 9 9h-9Z"/><path d="M15 3.6A9 9 0 0 1 20.4 9H15Z"/>`,
  star: `<path d="m12 4 2.5 5.1 5.6.8-4 3.9 1 5.6-5.1-2.7L6.9 19.4l1-5.6-4-3.9 5.6-.8Z"/>`,
  news: `<path d="M4.5 5.5h12v13h-12z"/><path d="M16.5 9H20v7.5a2 2 0 0 1-4 0Z"/><path d="M7 9h7"/><path d="M7 12.5h7"/><path d="M7 16h4"/>`,
  inbox: `<rect x="2.5" y="4.5" width="19" height="15" rx="3"/><path d="m3.5 7 7.3 5.2a2 2 0 0 0 2.4 0L20.5 7"/>`,
  dot: `<circle cx="12" cy="12" r="8"/><circle cx="12" cy="12" r="3" fill="currentColor" stroke="none"/>`,
};

function ico(name, size = 17) {
  return `<svg class="ico" viewBox="0 0 24 24" width="${size}" height="${size}" fill="none"
    stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">${ICONS[name] || ""}</svg>`;
}

/* 极简 markdown 渲染（摘要用）：标题/加粗/列表/换行 */
function miniMd(src) {
  const lines = esc(src).split("\n");
  let out = "", inList = false;
  for (let line of lines) {
    line = line.replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>");
    const m = line.match(/^\s*[-*]\s+(.*)/);
    if (m) { if (!inList) { out += "<ul>"; inList = true; } out += `<li>${m[1]}</li>`; continue; }
    if (inList) { out += "</ul>"; inList = false; }
    if (/^#{1,4}\s/.test(line)) out += `<p><strong>${line.replace(/^#+\s*/, "")}</strong></p>`;
    else if (line.trim()) out += `<p>${line}</p>`;
  }
  if (inList) out += "</ul>";
  return out;
}

/* ---------- 登录 ---------- */

function showLogin() {
  $("#app").classList.add("hidden");
  $("#login-view").classList.remove("hidden");
  $("#login-password").focus();
}

async function boot() {
  try {
    META = await api("/me");
    $("#login-view").classList.add("hidden");
    $("#app").classList.remove("hidden");
    if (!location.hash || location.hash === "#/") location.hash = "#/dashboard";
    route();
    if (!statusTimer) statusTimer = setInterval(refreshBadge, 45000);
    refreshBadge();
  } catch (e) { /* 401 已由 api() 处理 */ }
}

$("#login-form").addEventListener("submit", async ev => {
  ev.preventDefault();
  $("#login-error").textContent = "";
  try {
    await api("/login", { method: "POST", body: { password: $("#login-password").value } });
    $("#login-password").value = "";
    boot();
  } catch (e) { $("#login-error").textContent = e.message; }
});

$("#btn-logout").addEventListener("click", async () => {
  await api("/logout", { method: "POST" }).catch(() => {});
  showLogin();
});

$("#btn-sync-all").addEventListener("click", async () => {
  await api("/sync-all", { method: "POST" });
  toast("已触发全部账户同步");
});

async function refreshBadge() {
  try {
    const d = await api("/messages?unread=1&page_size=10");
    const b = $("#nav-unread");
    if (d.total > 0) { b.textContent = d.total > 99 ? "99+" : d.total; b.classList.remove("hidden"); }
    else b.classList.add("hidden");
  } catch (e) {}
  try {
    const q = await api("/review?limit=200");
    const rb = $("#nav-review");
    if (q.length) { rb.textContent = q.length > 99 ? "99+" : q.length; rb.classList.remove("hidden"); }
    else rb.classList.add("hidden");
  } catch (e) {}
}

/* ---------- 路由 ---------- */

const VIEWS = { dashboard: renderDashboard, inbox: renderInbox, otp: renderOtp,
  accounts: renderAccounts, safety: renderSafety, settings: renderSettings };

function route() {
  const name = (location.hash.replace("#/", "") || "dashboard").split("?")[0];
  const view = VIEWS[name] ? name : "dashboard";
  $$(".sidebar nav a").forEach(a => a.classList.toggle("active", a.dataset.view === view));
  $$(".view").forEach(v => v.classList.add("hidden"));
  $(`#view-${view}`).classList.remove("hidden");
  charts.forEach(c => c.dispose()); charts = [];
  clearInterval(otpTimer); otpTimer = null;
  VIEWS[view]();
}
window.addEventListener("hashchange", route);
window.addEventListener("resize", () => charts.forEach(c => c.resize()));

/* ---------- 仪表盘 ---------- */

/* 分类色（经色觉/对比度校验的浅色主题调色板，颜色跟随类目固定不变） */
const CAT_COLORS = { "验证码": "#C77800", "重要": "#D93025", "账单": "#9334E6", "重要账单": "#9334E6", "一般账单": "#BB94EE", "安全": "#D01884", "可疑": "#8C1D18", "订阅": "#1E8E3E", "通知": "#1A73E8", "其他": "#6C74B8", "未分类": "#9AA0A6" };
/* 堆叠顺序刻意隔开琥珀/红/绿等易混色相 */
const STACK_ORDER = ["验证码", "通知", "重要", "其他", "订阅", "一般账单", "重要账单", "安全", "可疑"];

/* 账单按等级显示为「重要账单」「一般账单」；定级理由只记在日志里，界面只显示结果 */
const BILL_TIERS = ["重要", "一般"];
function catName(m) {
  return m.category === "账单" && BILL_TIERS.includes(m.bill_tier) ? `${m.bill_tier}账单` : m.category;
}
function catBadge(m) {
  const name = catName(m);
  return `<span class="badge cat-${esc(name)}">${esc(name)}</span>`;
}
/* 收件箱分类筛选：账单拆成两项 */
function inboxCategories() {
  return (META.categories || []).flatMap(c => (c === "账单" ? BILL_TIERS.map(t => `${t}账单`) : [c]));
}
function categoryQuery(value) {
  const m = /^(重要|一般)账单$/.exec(value || "");
  return m ? { category: "账单", bill_tier: m[1] } : { category: value || "", bill_tier: "" };
}

async function renderDashboard() {
  const el = $("#view-dashboard");
  el.innerHTML = `<h2>仪表盘</h2><div class="empty">加载中…</div>`;
  let d, governanceStatus;
  try { [d, governanceStatus] = await Promise.all([api("/overview"), api("/governance/status")]); }
  catch (e) { el.innerHTML = `<div class="empty">${esc(e.message)}</div>`; return; }
  const s = d.stats;
  el.innerHTML = `
    <h2>仪表盘</h2>
    ${governanceCardHtml(governanceStatus)}
    <div class="grid stat-row">
      ${[
        { k: "accent", n: s.today, l: "今日新邮件", i: "inbox" },
        { k: "", n: s.unread, l: "未读", i: "dot" },
        { k: "warn", n: s.today_otp, l: "今日验证码", i: "key" },
        { k: "danger", n: s.important_unread, l: "未读重要/安全", i: "star" },
        { k: "", n: s.total, l: "邮件总数", i: "chart" },
      ].map(c => `<div class="card stat-card ${c.k}">
        <span class="stat-ico">${ico(c.i, 18)}</span>
        <div class="num">${c.n}</div><div class="lbl">${c.l}</div>
      </div>`).join("")}
    </div>
    <div class="grid dash-grid">
      <div class="card"><h3>${ico("chart")} 近 14 天邮件量</h3><div id="chart-daily" class="chart"></div></div>
      <div class="card"><h3>${ico("pie")} 分类分布</h3><div id="chart-cat" class="chart"></div></div>
    </div>
    <div class="grid dash-grid" style="margin-top:14px">
      <div class="card">
        <h3>${ico("star")} 重要邮件</h3>
        <div class="mail-list" id="dash-important">
          ${d.important.length ? d.important.map(mailRowHtml).join("") : `<div class="empty">暂无重要邮件 ✨</div>`}
        </div>
      </div>
      <div class="card">
        <h3>${ico("news")} 今日晨报 ${d.ai_enabled ? `<button id="btn-gen-digest" class="btn sm" style="margin-left:auto">重新生成</button>` : ""}</h3>
        ${d.digest
          ? `<div class="digest-day">${esc(d.digest.day)}</div><div class="digest-md">${miniMd(d.digest.content)}</div>`
          : `<div class="empty">${d.ai_enabled ? "尚未生成，每天 " + "早晨自动生成" : "在设置中启用 AI 后可自动生成每日晨报"}</div>`}
      </div>
    </div>`;
  bindGovernanceRun(el, renderDashboard);
  bindMailRows(el);
  $("#btn-gen-digest")?.addEventListener("click", async ev => {
    ev.target.disabled = true; ev.target.textContent = "生成中…";
    try { await api("/digest/generate", { method: "POST" }); toast("晨报已生成"); renderDashboard(); }
    catch (e) { toast(e.message, true); ev.target.disabled = false; ev.target.textContent = "重新生成"; }
  });

  // 图表（浅色主题：文字用墨色而非系列色，网格退后，段间留白色缝）
  const INK_MUTED = "#5f6368", INK_FAINT = "#80868b", GRID = "#eceff4";
  const dailyChart = echarts.init($("#chart-daily"), null, { renderer: "canvas" });
  dailyChart.setOption({
    backgroundColor: "transparent",
    textStyle: { fontFamily: "Outfit, 'Segoe UI', 'Microsoft YaHei', sans-serif" },
    tooltip: {
      trigger: "axis",
      backgroundColor: "#fff", borderColor: "#e4e9f0",
      textStyle: { color: "#1f1f1f", fontSize: 12 },
      extraCssText: "box-shadow:0 4px 12px rgba(60,64,67,.15);border-radius:12px",
    },
    legend: { textStyle: { color: INK_MUTED, fontSize: 11 }, itemWidth: 12, itemHeight: 8, icon: "roundRect" },
    grid: { left: 36, right: 10, top: 36, bottom: 24 },
    xAxis: {
      type: "category", data: d.days,
      axisLine: { lineStyle: { color: GRID } }, axisTick: { show: false },
      axisLabel: { color: INK_FAINT, fontSize: 10.5 },
    },
    yAxis: {
      type: "value",
      splitLine: { lineStyle: { color: GRID } },
      axisLabel: { color: INK_FAINT, fontSize: 10.5 },
    },
    series: STACK_ORDER.map(cat => ({
      name: cat, type: "bar", stack: "all", barMaxWidth: 22,
      itemStyle: { color: CAT_COLORS[cat], borderColor: "#ffffff", borderWidth: 1 },
      data: d.daily.map(row => row[cat] || 0),
    })),
  });
  const catData = STACK_ORDER.filter(k => s.by_category[k] > 0)
    .map(k => ({ name: k, value: s.by_category[k], itemStyle: { color: CAT_COLORS[k] } }));
  if (!catData.length) {
    // 无数据时画空环会显示成一坨灰色，不如直接给文案
    $("#chart-cat").innerHTML = `<div class="empty" style="padding:80px 0">还没有邮件数据</div>`;
    charts = [dailyChart];
    return;
  }
  const catChart = echarts.init($("#chart-cat"), null, { renderer: "canvas" });
  catChart.setOption({
    backgroundColor: "transparent",
    textStyle: { fontFamily: "Outfit, 'Segoe UI', 'Microsoft YaHei', sans-serif" },
    tooltip: {
      trigger: "item", formatter: "{b}: {c} ({d}%)",
      backgroundColor: "#fff", borderColor: "#e4e9f0",
      textStyle: { color: "#1f1f1f", fontSize: 12 },
      extraCssText: "box-shadow:0 4px 12px rgba(60,64,67,.15);border-radius:12px",
    },
    series: [{
      type: "pie", radius: ["46%", "72%"], center: ["50%", "52%"],
      label: { color: INK_MUTED, fontSize: 11, formatter: "{b} {c}" },
      labelLine: { lineStyle: { color: GRID } },
      itemStyle: { borderColor: "#ffffff", borderWidth: 2, borderRadius: 4 },
      data: catData,
    }],
  });
  charts = [dailyChart, catChart];
}

/* ---------- 收件箱 ---------- */

function mailRowHtml(m) {
  return `
  <div class="mail-row ${m.unread ? "unread" : ""}" data-id="${m.id}">
    <input type="checkbox" class="m-check" data-id="${m.id}">
    <span class="acc-dot" style="background:${esc(m.account_color || "#1a73e8")}" title="${esc(m.account_name || "")}"></span>
    <span class="m-sender" title="${esc(m.sender_addr)}">${esc(m.sender_name || m.sender_addr)}</span>
    <span class="m-main">
      ${catBadge(m)}
      ${m.otp_code ? `<span class="m-otp" data-otp="${esc(m.otp_code)}" title="点击复制">${esc(m.otp_code)}</span>` : ""}
      <span class="m-subject">${esc(m.subject || "(无主题)")}</span>
      <span class="m-snippet">${esc(m.summary || m.snippet || "")}</span>
    </span>
    <span class="m-time">${fmtTime(m.date_ts)}</span>
  </div>`;
}

function bindMailRows(root) {
  $$(".mail-row", root).forEach(row => {
    row.addEventListener("click", () => openMail(+row.dataset.id));
  });
  $$(".m-otp", root).forEach(o => o.addEventListener("click", ev => {
    ev.stopPropagation(); copyText(o.dataset.otp);
  }));
  // 勾选框不触发整行的"打开邮件"（不用内联 onclick，便于启用严格 CSP）
  $$(".m-check", root).forEach(cb => cb.addEventListener("click", ev => ev.stopPropagation()));
  $$(".m-check", root).forEach(cb => cb.addEventListener("change", () => {
    const id = +cb.dataset.id;
    cb.checked ? inboxState.selected.add(id) : inboxState.selected.delete(id);
    updateBatchBar();
  }));
}

function updateBatchBar() {
  const bar = $("#batch-bar");
  if (!bar) return;
  const n = inboxState.selected.size;
  bar.style.visibility = n ? "visible" : "hidden";
  $("#batch-count").textContent = n;
}

async function renderInbox() {
  const el = $("#view-inbox");
  const [accounts, governanceStatus] = await Promise.all([
    api("/accounts").catch(() => []),
    api("/governance/status").catch(() => ({ pending: 0, last_run: null })),
  ]);
  const cats = ["", ...inboxCategories()];
  el.innerHTML = `
    <h2>收件箱</h2>
    ${governanceCardHtml(governanceStatus)}
    <div class="toolbar">
      <div class="chips">${cats.map(c =>
        `<button class="chip ${inboxState.category === c ? "active" : ""}" data-cat="${c}">${c || "全部"}</button>`).join("")}
      </div>
      <div class="spacer"></div>
      <select id="f-account"><option value="0">全部账户</option>
        ${accounts.map(a => `<option value="${a.id}" ${inboxState.account === a.id ? "selected" : ""}>${esc(a.name)}</option>`).join("")}
      </select>
      <select id="f-unread">
        <option value="-1">全部状态</option>
        <option value="1" ${inboxState.unread === 1 ? "selected" : ""}>未读</option>
        <option value="0" ${inboxState.unread === 0 ? "selected" : ""}>已读</option>
      </select>
      <input type="search" id="f-q" placeholder="搜索主题 / 发件人…" value="${esc(inboxState.q)}">
    </div>
    <div class="toolbar" id="batch-bar" style="visibility:hidden">
      <span style="color:var(--muted);font-size:12px">已选 <b id="batch-count">0</b> 封</span>
      <button class="btn sm" data-batch="read">标为已读</button>
      <button class="btn sm" data-batch="unread">标为未读</button>
      <button class="btn sm danger" data-batch="delete">删除(仅本地)</button>
      <button class="btn sm danger" data-batch="delete-server">删除(含服务器)</button>
      <div class="spacer"></div>
      <button class="btn sm" id="btn-clean-otp">🧹 一键清理验证码</button>
    </div>
    <div id="mail-container"><div class="empty">加载中…</div></div>`;

  bindGovernanceRun(el, renderInbox);
  $$(".chip", el).forEach(ch => ch.addEventListener("click", () => {
    inboxState.category = ch.dataset.cat; inboxState.page = 1; renderInbox();
  }));
  $("#f-account").addEventListener("change", ev => { inboxState.account = +ev.target.value; inboxState.page = 1; loadMails(); });
  $("#f-unread").addEventListener("change", ev => { inboxState.unread = +ev.target.value; inboxState.page = 1; loadMails(); });
  let qTimer;
  $("#f-q").addEventListener("input", ev => {
    clearTimeout(qTimer);
    qTimer = setTimeout(() => { inboxState.q = ev.target.value.trim(); inboxState.page = 1; loadMails(); }, 400);
  });
  $$("[data-batch]", el).forEach(b => b.addEventListener("click", () => doBatch(b.dataset.batch)));
  $("#btn-clean-otp").addEventListener("click", cleanOtpDialog);
  loadMails();
}

async function loadMails() {
  const box = $("#mail-container");
  inboxState.selected.clear(); updateBatchBar();
  const p = new URLSearchParams({
    ...categoryQuery(inboxState.category), account: inboxState.account,
    unread: inboxState.unread, q: inboxState.q, page: inboxState.page, page_size: 40,
  });
  let d;
  try { d = await api("/messages?" + p); } catch (e) { box.innerHTML = `<div class="empty">${esc(e.message)}</div>`; return; }
  if (!d.items.length) {
    box.innerHTML = `<div class="empty"><div class="big">🍃</div>没有邮件${META.accounts === 0 ? "，先去「账户」页添加邮箱吧" : ""}</div>`;
    return;
  }
  const pages = Math.ceil(d.total / 40);
  box.innerHTML = `<div class="mail-list">${d.items.map(mailRowHtml).join("")}</div>
    <div class="pager">
      <button class="btn sm" id="pg-prev" ${inboxState.page <= 1 ? "disabled" : ""}>‹ 上一页</button>
      <span>${inboxState.page} / ${pages}（共 ${d.total} 封）</span>
      <button class="btn sm" id="pg-next" ${inboxState.page >= pages ? "disabled" : ""}>下一页 ›</button>
    </div>`;
  bindMailRows(box);
  $("#pg-prev").addEventListener("click", () => { inboxState.page--; loadMails(); });
  $("#pg-next").addEventListener("click", () => { inboxState.page++; loadMails(); });
}

async function doBatch(action) {
  const ids = [...inboxState.selected];
  if (!ids.length) return;
  const server = action === "delete-server";
  const act = server ? "delete" : action;
  if (act === "delete" && !confirm(`确认删除选中的 ${ids.length} 封邮件？${server ? "\n（将同时从邮箱服务器永久删除，不可撤销）" : "（移入回收站，可恢复）"}`)) return;
  try {
    const r = await api("/messages/batch", { method: "POST", body: { ids, action: act, server, confirmed: true } });
    let msg = `已处理 ${r.count} 封`;
    if (r.blocked?.length) msg += `，${r.blocked.length} 封被安全策略保护未删除`;
    if (r.errors?.length) msg += `，部分失败: ${r.errors[0]}`;
    toast(msg);
    loadMails(); refreshBadge();
  } catch (e) { toast(e.message, true); }
}

function cleanOtpDialog() {
  openModal(`
    <h3>${ico("broom")} 清理验证码邮件</h3>
    <label class="field"><span>清理范围</span>
      <select id="cl-days">
        <option value="1">1 天前的</option>
        <option value="3" selected>3 天前的</option>
        <option value="7">7 天前的</option>
        <option value="0">全部验证码邮件</option>
      </select></label>
    <label class="switch"><input type="checkbox" id="cl-server"> 同时从邮箱服务器永久删除（不可撤销）</label>
    <p style="font-size:12px;color:var(--faint);margin-top:8px">不勾选时仅移入回收站，可随时恢复。带附件或判为重要的邮件会被自动保护。</p>
    <div class="ops">
      <button class="btn" id="cl-cancel">取消</button>
      <button class="btn danger" id="cl-go">清理</button>
    </div>`);
  $("#cl-cancel").addEventListener("click", closeModal);
  $("#cl-go").addEventListener("click", async () => {
    const days = +$("#cl-days").value, server = $("#cl-server").checked;
    closeModal();
    try {
      const r = await api("/clean", { method: "POST", body: { category: "验证码", older_days: days, server, confirmed: true } });
      toast(`已清理 ${r.count} 封` + (r.blocked?.length ? `，${r.blocked.length} 封被保护` : "") + (r.errors?.length ? `（部分服务器删除失败）` : ""));
      loadMails();
    } catch (e) { toast(e.message, true); }
  });
}

/* ---------- 邮件详情 ---------- */

async function openMail(id) {
  let m;
  try { m = await api(`/messages/${id}`); } catch (e) { toast(e.message, true); return; }
  const drawer = $("#drawer"), mask = $("#drawer-mask");
  drawer.innerHTML = `
    <div class="d-head">
      <div class="d-sub">${esc(m.subject || "(无主题)")}</div>
      <div class="d-meta">
        <span id="d-cat">${catBadge(m)}</span>
        <span>${esc(m.sender_name || "")} &lt;${esc(m.sender_addr)}&gt;</span>
        <span>${new Date(m.date_ts * 1000).toLocaleString("zh-CN")}</span>
        <span style="color:${esc(m.account_color)}">● ${esc(m.account_name)}</span>
        ${m.has_attach ? "<span>📎 有附件（请去原邮箱下载）</span>" : ""}
      </div>
      ${m.summary ? `<div class="d-meta" style="margin-top:6px">AI 摘要：${esc(m.summary)}${m.confidence ? `（置信度 ${(m.confidence * 100).toFixed(0)}%）` : ""}</div>` : ""}
      ${m.ai_reason ? `<div class="d-meta">判定依据：${esc(m.ai_reason)}</div>` : ""}
      ${m.risk_level && m.risk_level !== "none" ? `<div class="risk-banner risk-${esc(m.risk_level)}">⚠ 风险等级 ${esc(m.risk_level)}：${esc(m.risk_reasons || "")}<br><small>本站不会自动访问其中的链接或打开附件</small></div>` : ""}
    </div>
    ${m.category === "账单" ? `<div class="d-bill-tier">
      <span>账单等级</span>
      <div class="seg" role="group" aria-label="账单等级">${BILL_TIERS.map(t =>
        `<button class="seg-btn ${m.bill_tier === t ? "active" : ""}" data-bill-tier="${t}">${t}</button>`).join("")}</div>
      <label class="d-bill-similar"><input type="checkbox" id="d-bill-similar" checked> 同类邮件以后都这样</label>
    </div>` : ""}
    ${m.otp_code ? `<div class="d-otp-line"><span>验证码</span><span class="code">${esc(m.otp_code)}</span>
      <button class="btn sm" id="d-copy-otp">复制</button></div>` : ""}
    <div class="d-ops">
      <button class="btn sm" id="d-unread">标为未读</button>
      <button class="btn sm danger" id="d-del">删除(仅本地)</button>
      <button class="btn sm danger" id="d-del-server">删除(含服务器)</button>
      <div style="flex:1"></div>
      <button class="btn sm" id="d-close">关闭 ✕</button>
    </div>
    <div class="d-body" id="d-body"></div>`;
  // HTML 邮件放进沙箱 iframe（禁脚本），纯文本直接展示
  const body = $("#d-body");
  if (m.body_html) {
    // 默认拦截一切外部资源：追踪像素会暴露"已读"时间与 IP，需用户显式放行
    const renderHtml = (allowRemote) => {
      body.innerHTML = "";
      if (!allowRemote) {
        const bar = document.createElement("div");
        bar.className = "remote-bar";
        bar.innerHTML = `<span>已拦截外部图片（防追踪像素泄露阅读行为与 IP）</span>
          <button class="btn sm" id="d-load-img">仍要加载</button>`;
        body.appendChild(bar);
      }
      const csp = `<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:${allowRemote ? " https:" : ""}; style-src 'unsafe-inline'">`;
      const iframe = document.createElement("iframe");
      iframe.setAttribute("sandbox", "");   // 全沙箱：禁脚本/表单/弹窗/同源
      iframe.setAttribute("referrerpolicy", "no-referrer");
      iframe.srcdoc = csp + `<base target="_blank"><meta charset="utf-8"><style>body{font-family:sans-serif;margin:16px;word-break:break-word}img{max-width:100%}</style>` + m.body_html;
      body.appendChild(iframe);
      $("#d-load-img")?.addEventListener("click", () => renderHtml(true));
    };
    renderHtml(false);
  } else {
    body.innerHTML = `<pre class="plain">${esc(m.body_text || "(无内容)")}</pre>`;
  }
  drawer.classList.remove("hidden"); mask.classList.remove("hidden");
  const close = () => { drawer.classList.add("hidden"); mask.classList.add("hidden"); refreshBadge(); };
  mask.onclick = close;
  $("#d-close").addEventListener("click", close);
  $("#d-copy-otp")?.addEventListener("click", () => copyText(m.otp_code));
  $$("[data-bill-tier]", drawer).forEach(b => b.addEventListener("click", async () => {
    const tier = b.dataset.billTier;
    if (tier === m.bill_tier) return;
    try {
      const r = await api(`/messages/${id}/bill-tier`, { method: "POST",
        body: { tier, similar: $("#d-bill-similar").checked } });
      m.bill_tier = tier;
      $$("[data-bill-tier]", drawer).forEach(x => x.classList.toggle("active", x.dataset.billTier === tier));
      $("#d-cat").innerHTML = catBadge(m);
      toast(`已设为${tier}账单` + (r.similar_updated ? `，同类 ${r.similar_updated} 封已一并更新` : ""));
      if (!$("#view-inbox").classList.contains("hidden")) loadMails();
    } catch (e) { toast(e.message, true); }
  }));
  $("#d-unread").addEventListener("click", async () => {
    await api("/messages/batch", { method: "POST", body: { ids: [id], action: "unread", server: true } }).catch(() => {});
    toast("已标为未读"); close();
    if (!$("#view-inbox").classList.contains("hidden")) loadMails();
  });
  const del = async server => {
    if (!confirm(server ? "永久删除？将同时从邮箱服务器删除，此操作不可撤销。" : "移入回收站？可在「回收站」中恢复。")) return;
    const send = override => api("/messages/batch", { method: "POST", body: {
      ids: [id], action: "delete", server, confirmed: true, override_protection: override } });
    try {
      try {
        await send(false);
      } catch (e) {
        // 受保护邮件（账单/安全/重要/带附件）需要针对这一封单独再确认一次
        if (!(e.status === 403 && e.protected)) throw e;
        if (!confirm(`这封邮件受保护：${e.message.replace(/^操作被安全策略拦截：/, "")}\n\n仍要删除这一封吗？`)) return;
        await send(true);
      }
      toast(server ? "已永久删除" : "已移入回收站"); close();
      if (!$("#view-inbox").classList.contains("hidden")) loadMails();
    } catch (e) { toast(e.message, true); }
  };
  $("#d-del").addEventListener("click", () => del(false));
  $("#d-del-server").addEventListener("click", () => del(true));
}

/* ---------- 验证码看板 ---------- */

async function renderOtp() {
  const el = $("#view-otp");
  const load = async () => {
    let list;
    try { list = await api("/otp"); } catch (e) { return; }
    el.innerHTML = `
      <h2>验证码看板 <small style="color:var(--muted);font-size:12px;font-weight:400">15 秒自动刷新 · 点码即复制</small></h2>
      ${list.length ? `<div class="grid otp-grid">${list.map(o => {
        const hot = Date.now() / 1000 - o.date_ts < 300;
        return `<div class="card otp-card ${hot ? "hot" : ""}">
          <div class="meta" style="color:${esc(o.account_color)}">● ${esc(o.account_name)}</div>
          <div class="code" data-otp="${esc(o.otp_code)}" title="点击复制">${esc(o.otp_code)}</div>
          <div class="meta" title="${esc(o.subject)}">${esc(o.sender_addr)}</div>
          <div class="meta">${esc(o.subject || "")}</div>
          <div class="fresh">${ago(o.date_ts)}</div>
        </div>`;
      }).join("")}</div>`
      : `<div class="empty"><div class="big">⚿</div>暂无验证码邮件</div>`}`;
    $$(".otp-card .code", el).forEach(c => c.addEventListener("click", () => copyText(c.dataset.otp)));
  };
  await load();
  otpTimer = setInterval(load, 15000);
}

/* ---------- 账户 ---------- */

async function renderAccounts() {
  const el = $("#view-accounts");
  let accounts;
  try { accounts = await api("/accounts"); } catch (e) { el.innerHTML = `<div class="empty">${esc(e.message)}</div>`; return; }
  el.innerHTML = `
    <h2>邮箱账户 <button class="btn primary sm" id="btn-add-acc" style="float:right">＋ 添加账户</button></h2>
    ${accounts.length ? `<div class="grid acc-grid">${accounts.map(a => {
      const st = a.sync_state || {};
      return `<div class="card acc-card">
        <div class="head">
          <span class="dot" style="background:${esc(a.color)}"></span>
          <div><div class="name">${esc(a.name)} <span class="state-dot ${st.state === "error" ? "error" : st.state === "syncing" ? "syncing" : ""}"></span></div>
          <div class="email">${esc(a.email)} · ${esc(META.providers[a.provider]?.label || a.provider)}${a.enabled ? "" : " · <b style='color:var(--amber)'>已停用</b>"}</div></div>
        </div>
        <div class="stats">
          <span>邮件 <b>${a.total}</b></span><span>未读 <b>${a.unread}</b></span>
          <span>上次同步 <b>${a.last_sync ? ago(a.last_sync) : "从未"}</b></span>
        </div>
        ${a.last_error ? `<div class="err">⚠ ${esc(a.last_error)}</div>` : ""}
        ${a.auth_type === "oauth" && a.oauth_reauth_required ? `<div class="err">⚠ 授权已失效，需要重新登录该账户</div>` : ""}
        <div class="ops">
          <button class="btn sm" data-op="sync" data-id="${a.id}">↻ 同步</button>
          <button class="btn sm" data-op="test" data-id="${a.id}">测试连接</button>
          ${a.auth_type === "oauth" ? `<button class="btn sm" data-op="reauth" data-id="${a.id}">重新授权</button>` : ""}
          <button class="btn sm" data-op="edit" data-id="${a.id}">编辑</button>
          <button class="btn sm danger" data-op="del" data-id="${a.id}">删除</button>
        </div>
      </div>`;
    }).join("")}</div>`
    : `<div class="empty"><div class="big">📮</div>还没有账户，点击右上角「添加账户」聚合你的 QQ / 163 / Gmail / Outlook 邮箱</div>`}`;
  $("#btn-add-acc").addEventListener("click", () => accountModal());
  $$("[data-op]", el).forEach(b => b.addEventListener("click", async () => {
    const id = +b.dataset.id, op = b.dataset.op;
    if (op === "sync") { await api(`/accounts/${id}/sync`, { method: "POST" }); toast("已触发同步"); setTimeout(renderAccounts, 1500); }
    if (op === "test") {
      b.disabled = true; b.textContent = "测试中…";
      const r = await api(`/accounts/${id}/test`, { method: "POST" }).catch(e => ({ ok: false, msg: e.message }));
      toast(r.ok ? "✓ 连接正常" : r.msg, !r.ok); renderAccounts();
    }
    if (op === "edit") {
      const acc = accounts.find(x => x.id === id);
      accountModal(acc);
    }
    if (op === "reauth") accountModal(accounts.find(x => x.id === id));
    if (op === "del" && confirm("删除该账户？本地已同步的邮件也会一并删除（不影响邮箱服务器）")) {
      await api(`/accounts/${id}`, { method: "DELETE" }); toast("已删除"); renderAccounts();
    }
  }));
}

const METHOD_TAGS = {
  oauth_browser: "OAuth 登录",
  oauth_device: "设备码登录",
  app_password: "授权码",
  password: "密码",
};

function detectAccountProvider(email, providers) {
  const domain = String(email || "").trim().toLowerCase().split("@").pop();
  if (!domain || !String(email || "").includes("@")) return "custom";
  for (const [key, provider] of Object.entries(providers || {})) {
    if ((provider.domains || []).map(x => String(x).toLowerCase()).includes(domain)) return key;
  }
  return "custom";
}

// OAuth 弹窗完成后，回调页通过 postMessage 通知这里（只接受同源消息）
window.addEventListener("message", ev => {
  if (ev.origin !== location.origin || ev.data?.type !== "mailhub-oauth") return;
  if (ev.data.ok) {
    closeModal();
    toast("邮箱登录成功，正在同步…");
    if (!$("#view-accounts").classList.contains("hidden")) renderAccounts();
  } else {
    const status = $("#ac-status");
    if (status) status.innerHTML = `<div class="err">${esc(ev.data.message || "邮箱登录失败")}</div>`;
    else toast(ev.data.message || "邮箱登录失败", true);
  }
});

/**
 * 添加、编辑和重新授权共用的账户弹窗。
 * 认证方式完全来自后端 /api/oauth/providers 的 methods 列表：
 * oauth_browser（弹窗登录）、oauth_device（设备码）、app_password / password（授权码或密码）。
 */
/* 账户弹窗里「去设置」直接展开对应的设置分区 */
function settingsLink(provider) {
  const section = { gmail: "google", outlook: "microsoft" }[provider];
  return section ? `#/settings?open=${section}` : "#/settings";
}

async function accountModal(acc = null) {
  const editing = !!acc;
  let providers;
  try { providers = (await api("/oauth/providers")).providers; }
  catch (e) { toast(e.message, true); return; }
  META.providers = providers;

  openModal(`
    <h3>${editing ? "编辑账户" : "添加邮箱账户"}</h3>
    ${editing ? "" : `<div class="account-onboard-note">输入邮箱即可自动匹配服务商和可用的登录方式。</div>`}
    <label class="field"><span>邮箱地址</span>
      <input id="ac-email" type="email" value="${esc(acc?.email || "")}" placeholder="you@example.com"
        autocomplete="email" ${editing ? "disabled" : ""}></label>
    <label class="field"><span>邮箱服务商</span>
      <select id="ac-provider" ${editing ? "disabled" : ""}>
        ${editing ? "" : '<option value="auto" selected>自动识别（推荐）</option>'}
        ${Object.entries(providers).map(([k, p]) =>
          `<option value="${esc(k)}" ${acc?.provider === k ? "selected" : ""}>${esc(p.label)}</option>`).join("")}
      </select></label>
    <div class="provider-detected" id="ac-detected"></div>
    <div id="ac-methods"></div>
    <div id="ac-method-body"></div>
    <div id="ac-status"></div>
    <details class="imap-advanced" ${editing ? "open" : ""}>
      <summary>显示名称、颜色和同步间隔</summary>
      <label class="field"><span>显示名称（可选）</span><input id="ac-name" value="${esc(acc?.name || "")}" placeholder="如：主力邮箱"></label>
      <label class="field"><span>标记颜色</span><input id="ac-color" type="color" value="${esc(acc?.color || "#1a73e8")}" style="height:36px;padding:2px"></label>
      <label class="field"><span>轮询间隔（秒）</span><input id="ac-poll" type="number" value="${acc?.poll_interval || 300}" min="60"></label>
      ${editing ? `<label class="switch"><input type="checkbox" id="ac-enabled" ${acc.enabled ? "checked" : ""}> 启用该账户</label>` : ""}
    </details>
    <div class="ops">
      <button class="btn" id="ac-cancel">${editing ? "取消" : "关闭"}</button>
      ${editing ? '<button class="btn primary" id="ac-save">保存</button>' : ""}
    </div>`);

  let current = { provider: "", method: "" };
  const providerKey = () => {
    if (editing) return acc.provider;
    const choice = $("#ac-provider").value;
    return choice === "auto" ? detectAccountProvider($("#ac-email").value, providers) : choice;
  };
  const commonFields = () => ({
    name: $("#ac-name").value.trim(),
    color: $("#ac-color").value || "#1a73e8",
    poll_interval: Math.max(60, +$("#ac-poll").value || 300),
  });
  const oauthPayload = () => ({
    provider: providerKey(), email: (editing ? acc.email : $("#ac-email").value).trim(),
    account_id: editing ? acc.id : 0, ...commonFields(),
  });
  const setStatus = html => { $("#ac-status").innerHTML = html; };

  const renderMethodBody = (p, method) => {
    const body = $("#ac-method-body");
    setStatus("");
    if (!method) {
      body.innerHTML = `<div class="err">${esc(p.unavailable || "当前没有可用的登录方式")}</div>
        ${p.oauth_hint ? `<div class="provider-help oauth-hint" style="margin-top:10px">${esc(p.oauth_hint)}
          <a href="${settingsLink(providerKey())}" id="ac-goto-settings">去设置</a></div>` : ""}`;
      $("#ac-goto-settings")?.addEventListener("click", closeModal);
      return;
    }
    if (method.type === "oauth_browser") {
      body.innerHTML = `
        <div class="provider-help">将在新窗口打开服务商的登录页面，授权后自动完成${editing ? "重新授权" : "添加"}，MailHub 不会接触你的登录密码。</div>
        <div class="ops" style="justify-content:flex-start;margin:0 0 12px"><button class="btn primary" id="ac-oauth">${esc(method.label)}</button></div>`;
      $("#ac-oauth").addEventListener("click", () => startBrowserOAuth(oauthPayload()));
      return;
    }
    if (method.type === "oauth_device") {
      body.innerHTML = `
        <div class="provider-help">获取一个设备码，在 Microsoft 登录页输入并确认后自动完成${editing ? "重新授权" : "添加"}。</div>
        <div class="ops" style="justify-content:flex-start;margin:0 0 12px"><button class="btn primary" id="ac-device-start">获取设备码</button></div>
        <div id="ac-device"></div>`;
      $("#ac-device-start").addEventListener("click", () => startDeviceOAuth(oauthPayload()));
      return;
    }
    // app_password / password
    const isOAuthAccount = editing && acc.auth_type === "oauth";
    const steps = (method.steps || []).map(s => `<li>${esc(s)}</li>`).join("");
    const links = (method.links || []).filter(l => /^https:\/\//i.test(l.url || ""));
    const linkHtml = links.map((l, i) =>
      `<a href="${esc(l.url)}" target="_blank" rel="noopener">${links.length > 1 ? `${i + 1}. ` : ""}${esc(l.label)} ↗</a>`).join("");
    body.innerHTML = `
      ${p.oauth_hint ? `<div class="provider-help oauth-hint">${esc(p.oauth_hint)}
        <a href="${settingsLink(providerKey())}" id="ac-goto-settings">去设置</a></div>` : ""}
      ${steps || linkHtml ? `<div class="provider-help">
        ${steps ? `<ol class="auth-steps">${steps}</ol>` : ""}
        ${linkHtml ? `<div class="auth-links">${linkHtml}</div>` : ""}
        ${method.troubleshoot ? `<div class="auth-trouble">${esc(method.troubleshoot)}</div>` : ""}
      </div>` : ""}
      ${providerKey() === "custom" ? `
        <label class="field"><span>IMAP 服务器（SSL）</span><input id="ac-host" value="${esc(acc?.imap_host || "")}" placeholder="imap.example.com"></label>
        <label class="field"><span>端口</span><input id="ac-port" type="number" value="${acc?.imap_port || 993}"></label>` : ""}
      <label class="field"><span>${esc(method.input_label || "密码")}${editing && !isOAuthAccount ? "（留空则不修改）" : ""}</span>
        <input id="ac-secret" type="password" autocomplete="new-password" placeholder="不会在页面中回显"></label>
      ${isOAuthAccount ? `<div class="provider-help">保存后该账户将从 OAuth 改为用${esc(method.input_label)}登录。</div>` : ""}
      ${editing ? "" : `<div class="ops" style="justify-content:flex-start;margin:0 0 12px"><button class="btn primary" id="ac-add">测试连接并添加</button></div>`}`;
    $("#ac-add")?.addEventListener("click", addSecretAccount);
    $("#ac-goto-settings")?.addEventListener("click", closeModal);
  };

  const paint = (force = false) => {
    const key = providerKey();
    const p = providers[key] || providers.custom;
    const hasAddress = editing || $("#ac-email").value.includes("@");
    const automatic = !editing && $("#ac-provider").value === "auto";
    $("#ac-detected").innerHTML = !hasAddress && automatic
      ? "填写邮箱地址后自动识别"
      : `${automatic ? '<span class="ok">✓ 已自动识别</span> ' : ""}${esc(p.label)}`
        + (p.note ? `<div class="provider-note">${esc(p.note)}</div>` : "");
    if (!hasAddress && automatic) {
      $("#ac-methods").innerHTML = ""; $("#ac-method-body").innerHTML = "";
      current = { provider: "", method: "" };
      return;
    }
    const methods = p.methods || [];
    let chosen = current.provider === key ? current.method : "";
    if (!methods.some(m => m.type === chosen)) {
      // 编辑时默认显示该账户现在使用的方式
      const preferOAuth = !editing || acc.auth_type === "oauth";
      const match = methods.find(m => m.type.startsWith("oauth") === preferOAuth);
      chosen = (match || methods[0] || {}).type || "";
    }
    if (!force && current.provider === key && current.method === chosen) return;
    current = { provider: key, method: chosen };
    $("#ac-methods").innerHTML = methods.length > 1
      ? `<div class="chips auth-methods">${methods.map(m =>
          `<button class="chip ${m.type === chosen ? "active" : ""}" data-method="${esc(m.type)}">${esc(METHOD_TAGS[m.type] || m.label)}</button>`).join("")}</div>`
      : "";
    $$("[data-method]", $("#ac-methods")).forEach(b => b.addEventListener("click", () => {
      current.method = b.dataset.method;
      paint(true);
    }));
    renderMethodBody(p, methods.find(m => m.type === chosen));
  };

  async function addSecretAccount(ev) {
    const email = $("#ac-email").value.trim();
    if (!email.includes("@")) { setStatus('<div class="err">请填写完整邮箱地址</div>'); return; }
    const body = {
      provider: providerKey(), email, ...commonFields(),
      secret: $("#ac-secret").value,
      imap_host: $("#ac-host")?.value.trim() || "",
      imap_port: +($("#ac-port")?.value || 993),
    };
    if (!body.secret) { setStatus('<div class="err">请填写授权码或密码</div>'); return; }
    ev.target.disabled = true; ev.target.textContent = "正在测试连接…";
    try {
      await api("/accounts", { method: "POST", body });
      closeModal(); toast("连接成功，账户已添加并开始同步"); renderAccounts();
    } catch (e) {
      setStatus(`<div class="err">${esc(e.message)}</div>`);
      ev.target.disabled = false; ev.target.textContent = "测试连接并添加";
    }
  }

  paint(true);
  if (!editing) {
    $("#ac-email").addEventListener("input", () => { if ($("#ac-provider").value === "auto") paint(); });
    $("#ac-provider").addEventListener("change", () => paint(true));
  }
  $("#ac-cancel").addEventListener("click", closeModal);
  $("#ac-save")?.addEventListener("click", async ev => {
    const body = {
      provider: acc.provider, email: acc.email, ...commonFields(),
      secret: $("#ac-secret")?.value || "",
      imap_host: $("#ac-host")?.value.trim() || acc.imap_host,
      imap_port: +($("#ac-port")?.value || acc.imap_port || 993),
      enabled: $("#ac-enabled").checked,
    };
    ev.target.disabled = true; ev.target.textContent = body.secret ? "正在测试连接…" : "保存中…";
    try {
      await api(`/accounts/${acc.id}`, { method: "PUT", body });
      closeModal(); toast("已保存"); renderAccounts();
    } catch (e) {
      setStatus(`<div class="err">${esc(e.message)}</div>`);
      ev.target.disabled = false; ev.target.textContent = "保存";
    }
  });
}

async function startBrowserOAuth(payload) {
  if (!payload.email.includes("@")) { toast("请填写完整邮箱地址", true); return; }
  // 弹窗必须在点击事件里同步打开，否则会被浏览器拦截；拿到授权地址后再跳转
  const popup = window.open("about:blank", "mailhub-oauth", "popup=yes,width=560,height=720");
  const status = $("#ac-status");
  try {
    const r = await api("/oauth/start", { method: "POST", body: payload });
    if (popup) {
      popup.location.replace(r.authorization_url);
      if (status) status.innerHTML = '<div class="provider-help">请在弹出的窗口完成登录，完成后这里会自动关闭。</div>';
    } else if (status) {
      status.innerHTML = `<div class="provider-help">浏览器拦截了弹窗，<a href="${esc(r.authorization_url)}" target="_blank" rel="noopener">点此继续登录</a></div>`;
    }
  } catch (e) {
    popup?.close();
    if (status) status.innerHTML = `<div class="err">${esc(e.message)}</div>`;
    else toast(e.message, true);
  }
}

async function startDeviceOAuth(payload) {
  if (!payload.email.includes("@")) { toast("请填写完整邮箱地址", true); return; }
  const box = $("#ac-device");
  let dc;
  try { dc = await api("/oauth/outlook/device/start", { method: "POST", body: payload }); }
  catch (e) { $("#ac-status").innerHTML = `<div class="err">${esc(e.message)}</div>`; return; }
  const url = dc.verification_uri_complete || dc.verification_uri;
  box.innerHTML = `
    <p style="font-size:13px">1. 打开 <a href="${esc(url)}" target="_blank" rel="noopener">${esc(dc.verification_uri)}</a></p>
    <p style="font-size:13px">2. 输入下方代码，并用 <b>${esc(payload.email)}</b> 登录：</p>
    <div class="device-code" title="点击复制">${esc(dc.user_code)}</div>
    <p id="ac-device-state" style="color:var(--muted);font-size:12px">等待授权中…（完成后自动关闭）</p>`;
  $(".device-code", box).addEventListener("click", () => copyText(dc.user_code));
  const startBtn = $("#ac-device-start");
  if (startBtn) startBtn.disabled = true;
  const deadline = Date.now() + (dc.expires_in || 900) * 1000;
  let interval = dc.interval || 5;
  const poll = async () => {
    const state = $("#ac-device-state");
    if (!state) return;                       // 弹窗已关闭
    if (Date.now() > deadline) {
      state.textContent = "设备码已过期，请重新获取";
      if (startBtn) startBtn.disabled = false;
      return;
    }
    try {
      const r = await api("/oauth/outlook/device/poll", { method: "POST", body: { transaction_id: dc.transaction_id } });
      if (r.pending) {
        interval = r.interval || interval;
        setTimeout(poll, interval * 1000);
        return;
      }
      closeModal(); toast("授权成功，正在同步…"); renderAccounts();
    } catch (e) {
      state.textContent = "授权失败：" + e.message;
      if (startBtn) startBtn.disabled = false;
    }
  };
  setTimeout(poll, interval * 1000);
}


/* ---------- 安全中心：自动风险观察 / 回收站 / 审计与撤销 ---------- */

let safetyTab = "risk";

async function renderSafety() {
  const el = $("#view-safety");
  el.innerHTML = `
    <h2>安全中心</h2>
    <div class="toolbar"><div class="chips">
      ${[["risk", "风险观察"], ["trash", "回收站"], ["audit", "审计与撤销"]]
        .map(([k, label]) => `<button class="chip ${safetyTab === k ? "active" : ""}" data-tab="${k}">${label}</button>`).join("")}
    </div></div>
    <div id="safety-body"><div class="empty">加载中…</div></div>`;
  $$("[data-tab]", el).forEach(b => b.addEventListener("click", () => {
    safetyTab = b.dataset.tab; renderSafety();
  }));
  const box = $("#safety-body");
  try {
    if (safetyTab === "risk") await paintRisk(box);
    else if (safetyTab === "trash") await paintTrash(box);
    else await paintAudit(box);
  } catch (e) { box.innerHTML = `<div class="empty">${esc(e.message)}</div>`; }
}

async function paintRisk(box) {
  const list = await api("/review");
  if (!list.length) {
    box.innerHTML = `<div class="empty"><div class="big">✓</div>没有被自动标记的风险邮件</div>`;
    return;
  }
  box.innerHTML = `<div class="risk-observe-note">这些邮件已由门下省自动裁决为“可疑”。这里只提供观察，不需要逐封确认，也不会自动打开链接或附件。</div>
    <div class="mail-list">${list.map(m => `
    <div class="card review-card">
      <div class="head">
        <span class="badge cat-${esc(m.category)}">${esc(m.category)}</span>
        ${m.risk_level && m.risk_level !== "none"
          ? `<span class="badge risk-${esc(m.risk_level)}">风险 ${esc(m.risk_level)}</span>` : ""}
        ${m.confidence ? `<span class="conf">置信度 ${(m.confidence * 100).toFixed(0)}%</span>` : ""}
        ${m.governance_ministry ? `<span class="conf">主责 ${esc(m.governance_ministry)}</span>` : ""}
        <span style="flex:1"></span>
        <span class="m-time">${fmtTime(m.date_ts)}</span>
      </div>
      <div class="subj" data-open="${m.id}">${esc(m.subject || "(无主题)")}</div>
      <div class="meta">${esc(m.sender_name || "")} &lt;${esc(m.sender_addr)}&gt; · ${esc(m.account_name)}</div>
      ${m.governance_reason || m.ai_reason ? `<div class="why">自动裁决：${esc(m.governance_reason || m.ai_reason)}</div>` : ""}
      ${m.risk_reasons ? `<div class="why risk">⚠ ${esc(m.risk_reasons)}</div>` : ""}
      <div class="ops">
        <button class="btn sm" data-open="${m.id}">查看全文</button>
      </div>
    </div>`).join("")}</div>`;
  $$("[data-open]", box).forEach(b => b.addEventListener("click", () => openMail(+b.dataset.open)));
}

async function paintTrash(box) {
  const list = await api("/trash");
  if (!list.length) {
    box.innerHTML = `<div class="empty"><div class="big">🗑</div>回收站是空的</div>`;
    return;
  }
  box.innerHTML = `
    <div class="toolbar"><button class="btn sm" id="restore-all">全部恢复</button>
      <span style="color:var(--faint);font-size:12px">回收站中的邮件仍保留在邮箱服务器上，恢复不会丢失任何内容</span></div>
    <div class="mail-list">${list.map(m => `
      <div class="mail-row">
        <span class="acc-dot" style="background:${esc(m.account_color || "#1a73e8")}"></span>
        <span class="m-sender">${esc(m.sender_addr)}</span>
        <span class="m-main">${catBadge(m)}
          <span class="m-subject">${esc(m.subject || "(无主题)")}</span></span>
        <span class="m-time">${ago(m.deleted_ts)}删除</span>
        <button class="btn sm" data-restore="${m.id}">恢复</button>
      </div>`).join("")}</div>`;
  const restore = async ids => {
    await api("/trash/restore", { method: "POST", body: { ids } });
    toast(`已恢复 ${ids.length} 封`); renderSafety(); refreshBadge();
  };
  $("#restore-all").addEventListener("click", () => restore(list.map(m => m.id)));
  $$("[data-restore]", box).forEach(b =>
    b.addEventListener("click", () => restore([+b.dataset.restore])));
}

async function paintAudit(box) {
  const d = await api("/audit?limit=80");
  if (!d.items.length) {
    box.innerHTML = `<div class="empty">暂无操作记录</div>`;
    return;
  }
  box.innerHTML = `<div class="audit-list">${d.items.map(a => `
    <div class="audit-row ${a.allowed ? "" : "blocked"}">
      <span class="tier tier-${esc(a.tier || "?")}">${esc(a.tier || "?")}</span>
      <span class="act">${esc(a.action)}</span>
      <span class="who">${esc(a.actor)}</span>
      <span class="cnt">${a.target_count} 封</span>
      <span class="why">${a.allowed ? "" : "已拦截 · "}${esc(a.reason || "")}</span>
      <span style="flex:1"></span>
      <span class="m-time">${fmtTime(a.ts)}</span>
      ${a.reversible && !a.undone
        ? `<button class="btn sm" data-undo="${a.id}">撤销</button>`
        : `<span class="undone">${a.undone ? "已撤销" : (a.allowed ? "不可撤销" : "")}</span>`}
    </div>`).join("")}</div>
    <div class="pager">共 ${d.total} 条记录</div>`;
  $$("[data-undo]", box).forEach(b => b.addEventListener("click", async () => {
    try { const r = await api(`/audit/${b.dataset.undo}/undo`, { method: "POST" });
      toast(`已撤销，恢复 ${r.restored} 封`); renderSafety(); refreshBadge(); }
    catch (e) { toast(e.message, true); }
  }));
}

/* ---------- 设置 ---------- */

/* 设置页分区的展开状态：只是本浏览器的偏好，读写失败时退回内存 */
const SETTINGS_OPEN_KEY = "mailhub.settings.open";
let settingsOpen = null;
function loadSettingsOpen() {
  if (settingsOpen) return settingsOpen;
  try { settingsOpen = new Set(JSON.parse(localStorage.getItem(SETTINGS_OPEN_KEY) || "[]")); }
  catch (e) { settingsOpen = new Set(); }
  return settingsOpen;
}
function saveSettingsOpen() {
  try { localStorage.setItem(SETTINGS_OPEN_KEY, JSON.stringify([...settingsOpen])); } catch (e) { /* 忽略 */ }
}

async function renderSettings() {
  const el = $("#view-settings");
  let s, rules;
  try { [s, rules] = await Promise.all([api("/settings"), api("/rules")]); }
  catch (e) { el.innerHTML = `<div class="empty">${esc(e.message)}</div>`; return; }
  const opened = loadSettingsOpen();
  // 从账户弹窗的「去设置」进入时（#/settings?open=microsoft）直接展开对应分区
  const focus = new URLSearchParams(location.hash.split("?")[1] || "").get("open") || "";
  if (focus) { opened.add(focus); saveSettingsOpen(); }
  const secOpen = key => (opened.has(key) ? "open" : "");
  const channelCount = [s.notify_bark_url, s.notify_tg_token_set && s.notify_tg_chat, s.notify_onepush_set]
    .filter(Boolean).length;
  const secStatus = {
    ai: s.ai_enabled ? `已启用 · ${s.ai_model || "未选模型"}` : "未启用",
    bill: `金额阈值 ¥${s.bill_amount_threshold}`,
    google: s.google_oauth_configured ? "已启用" : "未配置",
    microsoft: s.microsoft_oauth_configured ? "已启用"
      : (s.microsoft_oauth_device_configured ? "仅设备码" : "未配置"),
    deploy: "",
    notify: channelCount ? `${channelCount} 个通道` : "未配置",
    clean: s.clean_otp_days ? `验证码保留 ${s.clean_otp_days} 天` : "不自动清理",
    password: "",
    rules: rules.length ? `${rules.length} 条` : "暂无",
  };
  el.innerHTML = `
    <h2>设置</h2>
    <div class="set-list">
      <details class="card set-sec" data-sec="ai" ${secOpen("ai")}>
        <summary><h3>${ico("sparkle")} AI 智能分类与摘要</h3><span class="sec-status">${esc(secStatus.ai || "")}</span></summary>
        <div class="sec-body">
        <div class="set-row"><div class="l">启用 AI<small>由 AI 阅读邮件内容判定分类（规则作参考和兜底），并生成每日晨报</small></div>
          <label class="switch"><input type="checkbox" id="s-ai" ${s.ai_enabled ? "checked" : ""}></label></div>
        <div class="set-row"><div class="l">接口地址<small>OpenAI 兼容 /v1</small></div>
          <input id="s-base" value="${esc(s.ai_base_url)}"></div>
        <div class="set-row"><div class="l">API Key<small>${s.ai_key_set ? "已设置（留空不修改）" : "未设置"}</small></div>
          <input id="s-key" type="password" placeholder="sk-…"></div>
        <div class="set-row"><div class="l">模型</div>
          <div class="set-ctl">
            <input id="s-model" value="${esc(s.ai_model)}" list="model-list"><datalist id="model-list"></datalist>
            <button class="btn sm" id="s-fetch-models" title="从接口拉取模型列表">拉取</button>
          </div></div>
        <div class="set-row"><div class="l">发送正文<small>每封最多 1500 字，验证码和密码已遮蔽、链接只保留域名；关闭后仅发送主题+发件人（更隐私，分类稍差）</small></div>
          <label class="switch"><input type="checkbox" id="s-body" ${s.ai_send_body ? "checked" : ""}></label></div>
        <div class="set-row"><div class="l">AI 判定范围<small>注入和中高风险邮件始终不发给 AI</small></div>
          <select id="s-ai-scope" style="width:auto">${[
            ["all", "全部邮件"], ["uncertain", "规则拿不准的邮件"], ["other", "仅规则未识别的邮件"],
          ].map(([v, label]) => `<option value="${v}" ${s.ai_scope === v ? "selected" : ""}>${label}</option>`).join("")}</select></div>
        <div class="set-row"><div class="l">历史邮件回溯天数<small>升级后重新治理旧邮件时，只把这些天内的交给 AI；0 = 不限</small></div>
          <input id="s-ai-backfill" type="number" min="0" max="365" value="${s.ai_backfill_days}" style="max-width:90px"></div>
        <div class="set-row"><div class="l">晨报生成时间</div>
          <select id="s-hour">${Array.from({ length: 24 }, (_, h) =>
            `<option value="${h}" ${s.digest_hour === h ? "selected" : ""}>${String(h).padStart(2, "0")}:00</option>`).join("")}</select></div>
        <div class="ops" style="display:flex;gap:8px;justify-content:flex-end;margin-top:12px">
          <button class="btn sm" id="s-ai-test">测试连通</button>
          <button class="btn sm" id="s-classify-now">一键三省六部分拣</button>
        </div>
      </div></details>
        <details class="card set-sec" data-sec="bill" ${secOpen("bill")}>
          <summary><h3>${ico("ruler")} 账单分级</h3><span class="sec-status">${esc(secStatus.bill || "")}</span></summary>
          <div class="sec-body">
          <div class="provider-help">账单分为「重要账单」和「一般账单」：先识别交易本身（资金方向、谁发起、获得了什么、是否需要处理、金额），再按下面的规则定级。在邮件详情里改过的等级会被记住并用于同类邮件。</div>
          <div class="set-row"><div class="l">金额阈值（元）<small>折合人民币不低于此值的账单一律重要；0 = 不按金额判断</small></div>
            <input id="s-bill-amount" type="number" min="0" step="10" value="${s.bill_amount_threshold}" style="max-width:110px"></div>
          <div class="set-row"><div class="l">例行流水阈值<small>同类账单近 30 天达到这么多封、且不是本人主动付款时视为一般；0 = 不启用</small></div>
            <input id="s-bill-routine" type="number" min="0" value="${s.bill_routine_count}" style="max-width:90px"></div>
          <div class="set-row"><div class="l">自动续费扣款</div>
            <select id="s-bill-auto" style="width:auto">${BILL_TIERS.map(t =>
              `<option ${s.bill_auto_renew === t ? "selected" : ""}>${t}</option>`).join("")}</select></div>
          <div class="set-row"><div class="l">退款</div>
            <select id="s-bill-refund" style="width:auto">${BILL_TIERS.map(t =>
              `<option ${s.bill_refund === t ? "selected" : ""}>${t}</option>`).join("")}</select></div>
          <div class="set-row"><div class="l">一般账单保留天数<small>0 = 不自动清理；到期每天移入回收站（带附件的不动）</small></div>
            <input id="s-bill-clean" type="number" min="0" value="${s.clean_bill_minor_days}" style="max-width:90px"></div>
        </div></details>
        <details class="card set-sec" data-sec="google" ${secOpen("google")}>
          <summary><h3>${ico("key")} Google 登录导入</h3><span class="sec-status">${esc(secStatus.google || "")}</span></summary>
          <div class="sec-body">
          <div class="provider-help">
            这是与 Outlook App 相同的 OAuth + PKCE + XOAUTH2 方式。配置完成后直接使用 Google 网页授权，无需 Gmail 应用专用密码。必须创建本项目自己的 Web 客户端，Outlook 的 Client ID 和微软回调不能移植。
            <a href="https://console.cloud.google.com/apis/credentials" target="_blank" rel="noopener">打开 Google Cloud 凭据页面</a>
            <ol class="auth-steps" style="margin-top:6px">
              <li>启用 Gmail API，OAuth 同意屏幕选「外部」，范围添加 <code>https://mail.google.com/</code></li>
              <li>创建「Web 应用」类型的 OAuth 客户端，已获授权的重定向 URI 填下方回调地址</li>
              <li>把同意屏幕的发布状态改为「正式版 / In production」：保持「测试」时授权 7 天后失效，需要每周重新登录</li>
              <li>未经 Google 验证的应用登录时会提示「Google 未验证此应用」，点「高级 → 继续」即可；未验证应用总共最多 100 个用户</li>
            </ol>
          </div>
          ${s.google_oauth_configuration_error ? `<div class="provider-help" style="color:var(--danger)">${esc(s.google_oauth_configuration_error)}</div>` : ""}
          <div class="set-row"><div class="l">状态<small>${s.google_oauth_configured ? "已启用登录式导入" : "尚未配置完整客户端"}</small></div>
            <span class="badge ${s.google_oauth_configured ? "cat-通知" : "cat-未分类"}">${s.google_oauth_source === "environment" ? "环境变量" : (s.google_oauth_configured ? "设置页" : "未启用")}</span></div>
          <div class="set-row"><div class="l">回调地址<small>复制到 Google OAuth 客户端的已获授权重定向 URI</small></div>
            <div class="set-ctl"><input id="s-google-callback" value="${esc(s.google_oauth_callback_url)}" readonly class="select-on-click"><button class="btn sm" id="s-google-copy">复制</button></div></div>
          <div class="set-row"><div class="l">Client ID</div>
            <input id="s-google-id" value="${esc(s.google_oauth_client_id || "")}" ${s.google_oauth_source === "environment" ? "disabled" : ""}></div>
          <div class="set-row"><div class="l">Client Secret<small>${s.google_oauth_client_secret_set ? "已设置（留空不修改）" : "未设置"}</small></div>
            <input id="s-google-secret" type="password" placeholder="留空不修改" ${s.google_oauth_source === "environment" ? "disabled" : ""}></div>
          <div class="set-row"><div class="l">清除 Client Secret<small>清除后 Google 登录立即停用，已有账户需要重新配置</small></div>
            <label class="switch"><input type="checkbox" id="s-google-clear" ${s.google_oauth_source === "environment" ? "disabled" : ""}></label></div>
        </div></details>
        <details class="card set-sec" data-sec="microsoft" ${secOpen("microsoft")}>
          <summary><h3>${ico("key")} Microsoft 登录导入</h3><span class="sec-status">${esc(secStatus.microsoft || "")}</span></summary>
          <div class="sec-body">
          <div class="provider-help">
            在 Microsoft Entra 注册本项目自己的应用。Client ID 可启用设备码登录；再配置 Web 回调和 Client Secret 后，添加 Outlook 时也可直接使用浏览器登录。
            <a href="https://entra.microsoft.com/#view/Microsoft_AAD_RegisteredApps/ApplicationsListBlade" target="_blank" rel="noopener">打开 Microsoft Entra 应用注册</a>
            <ol class="auth-steps" style="margin-top:6px">
              <li>个人 Microsoft 账号不能再在目录外注册应用，需要先有 Entra 租户：注册 <a href="https://azure.microsoft.com/free/" target="_blank" rel="noopener">Azure 免费账户</a>（需信用卡和手机验证，不会扣费）即自带默认目录</li>
              <li>「应用注册 → 新注册」，支持的账户类型选「任何组织目录中的账户和个人 Microsoft 帐户」</li>
              <li>最简单的做法：在「身份验证」里把「允许公共客户端流」设为「是」，把「应用程序(客户端) ID」填到下方 Client ID 并保存，就能用设备码登录，不需要 Secret</li>
              <li>需要浏览器登录时：再添加「Web」平台，重定向 URI 填下方回调地址；在「证书和密码」新建客户端密码，把「值」填到 Client Secret</li>
              <li>API 权限不用手动添加，登录时会请求 IMAP 访问和 offline_access</li>
            </ol>
          </div>
          <div class="set-row"><div class="l">网页登录<small>${s.microsoft_oauth_configured ? "已启用授权码 + PKCE" : "需要 Client ID 和 Client Secret"}</small></div>
            <span class="badge ${s.microsoft_oauth_configured ? "cat-通知" : "cat-未分类"}">${s.microsoft_oauth_configured ? "已启用" : "未启用"}</span></div>
          <div class="set-row"><div class="l">设备码登录<small>${s.microsoft_oauth_device_configured ? "使用本项目 Client ID" : "需要 Client ID 并开启公共客户端流"}</small></div>
            <span class="badge ${s.microsoft_oauth_device_configured ? "cat-通知" : "cat-未分类"}">${s.microsoft_oauth_device_configured ? "已启用" : "未启用"}</span></div>
          <div class="set-row"><div class="l">配置来源</div>
            <span class="badge cat-未分类">${s.microsoft_oauth_source === "environment" ? "环境变量" : (s.microsoft_oauth_source === "settings" ? "设置页" : "未配置")}</span></div>
          <div class="set-row"><div class="l">回调地址<small>复制到 Web 平台的重定向 URI</small></div>
            <div class="set-ctl"><input id="s-microsoft-callback" value="${esc(s.microsoft_oauth_callback_url)}" readonly class="select-on-click"><button class="btn sm" id="s-microsoft-copy">复制</button></div></div>
          <div class="set-row"><div class="l">Client ID</div>
            <input id="s-microsoft-id" value="${esc(s.microsoft_oauth_client_id || "")}" ${s.microsoft_oauth_source === "environment" ? "disabled" : ""}></div>
          <div class="set-row"><div class="l">Client Secret<small>${s.microsoft_oauth_client_secret_set ? "已设置（留空不修改）" : "网页登录尚未设置"}</small></div>
            <input id="s-microsoft-secret" type="password" placeholder="留空不修改" ${s.microsoft_oauth_source === "environment" ? "disabled" : ""}></div>
          <div class="set-row"><div class="l">清除 Client Secret<small>清除后仍可保留设备码登录</small></div>
            <label class="switch"><input type="checkbox" id="s-microsoft-clear" ${s.microsoft_oauth_source === "environment" ? "disabled" : ""}></label></div>
        </div></details>
        <details class="card set-sec" data-sec="deploy" ${secOpen("deploy")}>
          <summary><h3>${ico("shield")} 登录通道自检</h3><span class="sec-status">${esc(secStatus.deploy || "")}</span></summary>
          <div class="sec-body">
          <div id="s-deploy"><div class="provider-help">读取中…</div></div>
        </div></details>
        <details class="card set-sec" data-sec="notify" ${secOpen("notify")}>
          <summary><h3>${ico("bell")} 通知推送</h3><span class="sec-status">${esc(secStatus.notify || "")}</span></summary>
          <div class="sec-body">
          <div class="set-row"><div class="l">Bark 推送地址<small>如 https://api.day.app/你的Key</small></div>
            <input id="s-bark" value="${esc(s.notify_bark_url)}" placeholder="留空不启用"></div>
          <div class="set-row"><div class="l">Telegram Bot Token<small>${s.notify_tg_token_set ? "已设置（留空不修改）" : "未设置"}</small></div>
            <input id="s-tg-token" type="password" placeholder="123456:ABC…"></div>
          <div class="set-row"><div class="l">Telegram Chat ID</div>
            <input id="s-tg-chat" value="${esc(s.notify_tg_chat)}" placeholder="留空不启用"></div>
          <div class="set-row" style="display:block"><div class="l">Onepush 推送<small>${s.notify_onepush_set
              ? `已设置：${esc(s.notify_onepush_provider || "未知服务商")}（留空不修改）`
              : "未设置"}。YAML 格式，与 <a href="https://github.com/LmeSzinc/AzurLaneAutoScript/wiki/Onepush-configuration-%5BCN%5D" target="_blank" rel="noopener">Alas 的 Onepush 配置</a>相同，可用 Bark、Server酱 Turbo、pushplus、Telegram、Discord、企业微信、钉钉、飞书、go-cqhttp、Qmsg、SMTP 邮件、自定义 Webhook 等 onepush 支持的服务商；保存时会校验必填参数</small></div>
            <textarea id="s-onepush" rows="5" spellcheck="false" autocomplete="off"
              style="width:100%;margin-top:8px;font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12.5px"
              placeholder="provider: serverchanturbo&#10;sctkey: SCT16xxxxxxxxxxxx"></textarea>
            ${s.notify_onepush_set ? `<label style="display:flex;gap:6px;align-items:center;margin-top:6px;font-size:12px;color:var(--muted)">
              <input type="checkbox" id="s-onepush-clear" style="width:auto"> 清除已保存的 Onepush 配置</label>` : ""}</div>
          <div class="set-row"><div class="l">推送重要/安全邮件</div>
            <label class="switch"><input type="checkbox" id="s-ntf-imp" ${s.notify_important ? "checked" : ""}></label></div>
          <div class="set-row"><div class="l">推送验证码<small>验证码多时会比较吵</small></div>
            <label class="switch"><input type="checkbox" id="s-ntf-otp" ${s.notify_otp ? "checked" : ""}></label></div>
          <div style="text-align:right;margin-top:8px"><button class="btn sm" id="s-ntf-test">发送测试推送</button></div>
          <div id="s-ntf-result" style="font-size:12px;margin-top:6px;text-align:right"></div>
        </div></details>
        <details class="card set-sec" data-sec="clean" ${secOpen("clean")}>
          <summary><h3>${ico("broom")} 自动清理</h3><span class="sec-status">${esc(secStatus.clean || "")}</span></summary>
          <div class="sec-body">
          <div class="set-row"><div class="l">验证码邮件保留天数<small>0 = 不自动清理；到期每天自动删除</small></div>
            <input id="s-clean-days" type="number" min="0" value="${s.clean_otp_days}" style="max-width:90px"></div>
          <div class="set-row"><div class="l">同步删除服务器邮件<small>开启后自动清理会连同邮箱服务器一起删</small></div>
            <label class="switch"><input type="checkbox" id="s-clean-server" ${s.clean_server ? "checked" : ""}></label></div>
          <div class="set-row"><div class="l">正文保留天数<small>超期只清正文、保留标题/摘要/验证码，给小硬盘瘦身</small></div>
            <input id="s-body-days" type="number" min="0" value="${s.body_keep_days}" style="max-width:90px"></div>
        </div></details>
        <details class="card set-sec" data-sec="password" ${secOpen("password")}>
          <summary><h3>${ico("key")} 修改管理密码</h3><span class="sec-status">${esc(secStatus.password || "")}</span></summary>
          <div class="sec-body">
          <div class="set-row"><div class="l">原密码</div><input id="s-old-pwd" type="password"></div>
          <div class="set-row"><div class="l">新密码<small>至少 8 位；修改后其他设备需要重新登录</small></div><input id="s-new-pwd" type="password"></div>
          <div class="set-row"><div class="l">登录会话<small>怀疑密码或 Cookie 泄露时使用</small></div>
            <button class="btn sm danger" id="s-revoke-sessions">退出所有其他设备</button></div>
        </div></details>
        <details class="card set-sec" data-sec="rules" ${secOpen("rules")}>
          <summary><h3>${ico("ruler")} 自定义分类规则 <small style="font-weight:400">优先于内置规则</small></h3><span class="sec-status">${esc(secStatus.rules || "")}</span></summary>
          <div class="sec-body">
          <div id="rule-list">${rules.length ? rules.map(r => `
            <div class="rule-row">
              <span class="badge cat-${esc(r.category)}">${esc(r.category)}</span>
              <span>${esc(r.field)}</span><code>${esc(r.pattern)}</code>
              <span style="flex:1"></span>
              <button class="btn sm danger" data-rule-del="${r.id}">✕</button>
            </div>`).join("") : `<div style="color:var(--muted);font-size:12px;padding:6px 0">暂无规则</div>`}
          </div>
          <div style="display:flex;gap:6px;margin-top:10px;flex-wrap:wrap">
            <select id="r-field" style="width:auto"><option value="sender">发件人</option><option value="subject">主题</option><option value="body">正文</option></select>
            <input id="r-pattern" placeholder="正则，如 @github\\.com$" style="flex:1;min-width:120px">
            <select id="r-cat" style="width:auto">${META.categories.map(c => `<option>${c}</option>`).join("")}</select>
            <button class="btn sm primary" id="r-add">添加</button>
          </div>
        </div></details>
    </div>
    <div style="margin-top:16px;text-align:right;max-width:920px">
      <button class="btn primary" id="s-save">保存全部设置</button>
    </div>`;

  $("#s-save").addEventListener("click", async () => {
    const body = {
      ai_enabled: $("#s-ai").checked,
      ai_base_url: $("#s-base").value.trim(),
      ai_model: $("#s-model").value.trim(),
      ai_send_body: $("#s-body").checked,
      ai_scope: $("#s-ai-scope").value,
      ai_backfill_days: +$("#s-ai-backfill").value || 0,
      clean_otp_days: +$("#s-clean-days").value || 0,
      clean_server: $("#s-clean-server").checked,
      digest_hour: +$("#s-hour").value,
      notify_bark_url: $("#s-bark").value.trim(),
      notify_tg_chat: $("#s-tg-chat").value.trim(),
      notify_important: $("#s-ntf-imp").checked,
      notify_otp: $("#s-ntf-otp").checked,
      body_keep_days: +$("#s-body-days").value || 0,
      bill_amount_threshold: Math.max(0, +$("#s-bill-amount").value || 0),
      bill_routine_count: Math.max(0, +$("#s-bill-routine").value || 0),
      bill_auto_renew: $("#s-bill-auto").value,
      bill_refund: $("#s-bill-refund").value,
      clean_bill_minor_days: Math.max(0, +$("#s-bill-clean").value || 0),
    };
    const googleId = $("#s-google-id");
    if (googleId && !googleId.disabled) {
      body.google_oauth_client_id = googleId.value.trim();
      body.clear_google_oauth_client_secret = $("#s-google-clear").checked;
      const googleSecret = $("#s-google-secret").value.trim();
      if (googleSecret) body.google_oauth_client_secret = googleSecret;
    }
    const microsoftId = $("#s-microsoft-id");
    if (microsoftId && !microsoftId.disabled) {
      body.microsoft_oauth_client_id = microsoftId.value.trim();
      body.clear_microsoft_oauth_client_secret = $("#s-microsoft-clear").checked;
      const microsoftSecret = $("#s-microsoft-secret").value.trim();
      if (microsoftSecret) body.microsoft_oauth_client_secret = microsoftSecret;
    }
    const key = $("#s-key").value.trim();
    if (key) body.ai_key = key;
    const tgToken = $("#s-tg-token").value.trim();
    if (tgToken) body.notify_tg_token = tgToken;
    const onepush = $("#s-onepush").value.trim();
    if (onepush) body.notify_onepush_config = onepush;
    if ($("#s-onepush-clear")?.checked) body.clear_notify_onepush = true;
    const oldP = $("#s-old-pwd").value, newP = $("#s-new-pwd").value;
    if (newP) { body.old_password = oldP; body.new_password = newP; }
    try { await api("/settings", { method: "PUT", body }); toast("已保存"); renderSettings(); }
    catch (e) { toast(e.message, true); }
  });
  $("#s-revoke-sessions").addEventListener("click", async () => {
    if (!confirm("所有其他设备上的登录都将失效，确认？")) return;
    try {
      const r = await api("/sessions/revoke-all", { method: "POST" });
      toast(`已让 ${r.revoked} 个会话失效，当前设备已自动重新登录`);
    } catch (e) { toast(e.message, true); }
  });
  $("#s-fetch-models").addEventListener("click", async ev => {
    ev.target.disabled = true;
    try {
      const models = await api("/ai/models");
      $("#model-list").innerHTML = models.map(m => `<option value="${esc(m)}">`).join("");
      toast(`获取到 ${models.length} 个模型，在模型输入框中选择`);
    } catch (e) { toast(e.message, true); }
    ev.target.disabled = false;
  });
  $("#s-ai-test").addEventListener("click", async ev => {
    ev.target.disabled = true; ev.target.textContent = "测试中…";
    const r = await api("/ai/test", { method: "POST" }).catch(e => ({ ok: false, msg: e.message }));
    toast(r.ok ? `✓ AI 连通正常: ${r.reply}` : r.msg, !r.ok);
    ev.target.disabled = false; ev.target.textContent = "测试连通";
  });
  $("#s-classify-now").addEventListener("click", async ev => {
    ev.target.disabled = true; ev.target.textContent = "三省会审中…";
    try { await runGovernanceFlow(); }
    catch (e) { toast(e.message, true); }
    ev.target.disabled = false; ev.target.textContent = "一键三省六部分拣";
  });
  $("#s-microsoft-copy").addEventListener("click", () => copyText($("#s-microsoft-callback").value));
  $("#s-google-copy")?.addEventListener("click", () => copyText($("#s-google-callback").value));
  $("#s-ntf-test").addEventListener("click", async ev => {
    ev.target.disabled = true;
    // Onepush 输入框里有内容时，直接用它测试（不必先保存）；否则测试已保存的全部通道
    const onepush = $("#s-onepush")?.value.trim() || "";
    const r = await api("/notify/test", { method: "POST", body: onepush ? { onepush_config: onepush } : undefined })
      .catch(e => ({ ok: false, msg: e.message }));
    toast(r.msg, !r.ok);
    $("#s-ntf-result").innerHTML = `<span style="color:${r.ok ? "#137333" : "var(--danger)"}">${esc(r.msg)}</span>`;
    ev.target.disabled = false;
  });
  $("#r-add").addEventListener("click", async () => {
    try {
      await api("/rules", { method: "POST", body: {
        field: $("#r-field").value, pattern: $("#r-pattern").value, category: $("#r-cat").value,
      }});
      toast("规则已添加"); renderSettings();
    } catch (e) { toast(e.message, true); }
  });
  $$("[data-rule-del]", el).forEach(b => b.addEventListener("click", async () => {
    await api(`/rules/${b.dataset.ruleDel}`, { method: "DELETE" }); renderSettings();
  }));
  $$("details.set-sec", el).forEach(d => d.addEventListener("toggle", () => {
    if (d.open) opened.add(d.dataset.sec); else opened.delete(d.dataset.sec);
    saveSettingsOpen();
  }));
  if (focus) {
    $(`details.set-sec[data-sec="${CSS.escape(focus)}"]`, el)?.scrollIntoView({ block: "start", behavior: "smooth" });
    history.replaceState(null, "", "#/settings");
  }
  loadDeployStatus();
}

async function loadDeployStatus() {
  const box = $("#s-deploy");
  if (!box) return;
  let st;
  try { st = await api("/oauth/status"); }
  catch (e) { box.innerHTML = `<div class="err">${esc(e.message)}</div>`; return; }
  const badge = c => c.ok
    ? '<span class="badge cat-通知">通过</span>'
    : (c.optional ? '<span class="badge cat-未分类">可选</span>'
      : '<span class="badge" style="color:var(--danger)">失败</span>');
  const checks = (st.checks || []).map(c =>
    `<div class="set-row"><div class="l">${esc(c.name)}<small>${esc(c.detail || "")}</small></div>${badge(c)}</div>`).join("");
  const channels = Object.entries(st.channels || {}).map(([, v]) => {
    const methods = v.methods || [];
    const mode = methods.length ? methods.map(m => METHOD_TAGS[m] || m).join(" / ") : "暂不可用";
    const oauth = methods.some(m => m.startsWith("oauth"));
    return `<div class="set-row"><div class="l">${esc(v.label)}<small>${esc(mode)}</small></div>`
      + `<span class="badge ${oauth ? "cat-通知" : "cat-未分类"}">${oauth ? "OAuth" : (methods.length ? "授权码" : "不可用")}</span></div>`;
  }).join("");
  const reauth = st.accounts?.reauth_required || [];
  const status = box.closest("details")?.querySelector(".sec-status");
  if (status) status.textContent = !st.ok ? "有失败项" : (reauth.length ? `${reauth.length} 个账户待重新授权` : "全部通过");
  box.innerHTML = `
    <div class="provider-help">部署完成后打开本页即可确认回调域名、各服务商登录方式和待重新授权账户。</div>
    ${reauth.length ? `<div class="err">⚠ ${reauth.length} 个账户需要重新授权：${esc(reauth.map(r => `${r.provider}/${r.email}`).join("、"))}</div>` : ""}
    ${checks}${channels}`;
}

/* ---------- 弹窗 ---------- */

function openModal(html) {
  $("#modal").innerHTML = html;
  $("#modal-mask").classList.remove("hidden");
}
function closeModal() {
  $("#modal-mask").classList.add("hidden");
  $("#modal").innerHTML = "";
}
$("#modal-mask").addEventListener("click", ev => { if (ev.target.id === "modal-mask") closeModal(); });

/* ---------- 启动 ---------- */
boot();
