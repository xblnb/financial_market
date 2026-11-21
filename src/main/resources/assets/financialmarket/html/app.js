/* Kronos Markets — modern trading terminal.
   Vanilla JS + ECharts, no build step. */
(() => {
  'use strict';

  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
  const app = $('#app');
  const state = {
    route: 'dashboard',
    search: '',
    stocks: [],
    commodities: [],
    bankCard: {},
    bankStatement: [],
    banks: [],
    futures: [],
    futuresPositions: { positions: [], freeCash: 0, frozenMargin: 0, equity: 0 },
    account: { playerName: '—', cash: 0, totalMarketValue: 0, totalAssets: 0 },
    index: { value: 0, change: 0 },
    positions: [],
    orders: [],
    companies: [],
    macro: {},
    worldmacro: {},
    crossmacro: {},
    ak: {},
    czce: {},
    selectedStock: null,
    selectedFuture: null,
    stockPeriod: 4,
    options: null,
    optionCode: null,
    optionExpiry: 60,
    chart: null,
    online: false,
    loading: true,
  };

  const fmtNum = (v, d = 2) => {
    const n = Number(v);
    if (!Number.isFinite(n)) return '—';
    return n.toLocaleString('zh-CN', { minimumFractionDigits: d, maximumFractionDigits: d });
  };
  const fmtInt = v => {
    const n = Number(v);
    return Number.isFinite(n) ? n.toLocaleString('zh-CN', { maximumFractionDigits: 0 }) : '—';
  };
  const fmtMoney = v => {
    const n = Number(v);
    if (!Number.isFinite(n)) return '—';
    if (Math.abs(n) >= 1e8) return (n / 1e8).toFixed(2) + ' 亿';
    if (Math.abs(n) >= 1e4) return (n / 1e4).toFixed(2) + ' 万';
    return n.toLocaleString('zh-CN', { maximumFractionDigits: 2 });
  };
  const sign = v => Number(v) > 0 ? 'up' : Number(v) < 0 ? 'down' : 'flat';
  const signed = v => `${Number(v) > 0 ? '+' : ''}${fmtNum(v)}`;
  const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[c]));

  async function api(path, opts = {}) {
    const res = await fetch(path, {
      headers: { 'Content-Type': 'application/json', ...(opts.headers || {}) },
      ...opts,
    });
    const text = await res.text();
    let data;
    try { data = text ? JSON.parse(text) : null; } catch { data = text; }
    if (!res.ok) throw new Error((data && data.message) || `HTTP ${res.status}`);
    return data;
  }

  function toast(msg, isError = false) {
    const el = $('#toast');
    el.textContent = msg;
    el.className = 'toast show' + (isError ? ' error' : '');
    clearTimeout(toast._t);
    toast._t = setTimeout(() => { el.className = 'toast'; }, 3200);
  }

  function modal(title, html) {
    $('#modalTitle').textContent = title;
    $('#modalBody').innerHTML = html;
    $('#modal').classList.remove('hidden');
  }
  function closeModal() { $('#modal').classList.add('hidden'); }

  function setOnline(ok) {
    state.online = ok;
    const dot = $('#serverDot');
    dot.className = 'server-dot ' + (ok ? 'ok' : 'err');
    $('#serverText').textContent = ok ? '行情已连接' : '连接失败';
  }

  function sparkline(data, up = true, w = 86, h = 24) {
    const arr = (data || []).map(Number).filter(Number.isFinite);
    if (arr.length < 2) return '<svg class="spark" viewBox="0 0 86 24"></svg>';
    const min = Math.min(...arr), max = Math.max(...arr);
    const span = max - min || 1;
    const pts = arr.map((v, i) => {
      const x = (i / (arr.length - 1)) * (w - 2) + 1;
      const y = h - 2 - ((v - min) / span) * (h - 4);
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    }).join(' ');
    const color = up ? 'var(--red)' : 'var(--green)';
    return `<svg class="spark" viewBox="0 0 ${w} ${h}" preserveAspectRatio="none"><polyline points="${pts}" fill="none" stroke="${color}" stroke-width="1.5" stroke-linejoin="round" stroke-linecap="round"/></svg>`;
  }

  function pctChange(v) {
    const n = Number(v);
    return `<span class="${sign(n)} mono">${n > 0 ? '+' : ''}${fmtNum(n)}%</span>`;
  }

  function emptyRow(cols, text = '暂无数据') {
    return `<tr><td colspan="${cols}"><div class="empty">${esc(text)}</div></td></tr>`;
  }

  function routeFromHash() {
    const h = (location.hash || '#/dashboard').replace(/^#\/?/, '').split('?')[0];
    return ['dashboard', 'stocks', 'commodities', 'bank', 'futures', 'options', 'portfolio', 'orders', 'macro', 'companies'].includes(h) ? h : 'dashboard';
  }

  function setRoute(route, opts = {}) {
    state.route = route;
    if (!opts.silent) location.hash = '#/' + route;
    $$('#nav .nav-item, #mobileNav a').forEach(el => {
      el.classList.toggle('active', el.dataset.route === route);
    });
    $('#sidebar').classList.remove('open');
    render();
  }

  async function loadCore() {
    const tasks = [
      api('/api/account').then(v => state.account = v || {}),
      api('/api/index').then(v => state.index = v || {}),
      api('/api/stocks').then(v => state.stocks = Array.isArray(v) ? v : []),
      api('/api/commodities').then(v => state.commodities = Array.isArray(v) ? v : []),
      api('/api/bank/card').then(v => state.bankCard = v || {}),
      api('/api/bank/statement').then(v => state.bankStatement = Array.isArray(v) ? v : []),
      api('/api/banks').then(v => state.banks = Array.isArray(v) ? v : []),
      api('/api/futures').then(v => state.futures = Array.isArray(v) ? v : []),
      api('/api/czce').then(v => state.czce = v || {}),
      api('/api/macro').then(v => state.macro = v || {}),
      api('/api/worldmacro').then(v => state.worldmacro = v || {}),
      api('/api/crossmacro').then(v => state.crossmacro = v || {}),
      api('/api/ak').then(v => state.ak = v || {}),
      api('/api/companies').then(v => state.companies = Array.isArray(v) ? v : []),
      api('/api/positions').then(v => state.positions = Array.isArray(v) ? v : []),
      api('/api/futures/positions').then(v => state.futuresPositions = v || { positions: [] }),
    ];
    const results = await Promise.allSettled(tasks);
    setOnline(results.some(r => r.status === 'fulfilled'));
    if (!state.selectedStock && state.stocks.length) state.selectedStock = state.stocks[0].stockId;
    if (!state.selectedFuture && state.futures.length) state.selectedFuture = state.futures[0].code;
    if (!state.optionCode && state.futures.length) state.optionCode = state.futures[0].code;
    $('#topEquity').textContent = state.account.totalAssets ? fmtMoney(state.account.totalAssets) : '—';
    $('#avatar').textContent = (state.account.playerName || 'P').slice(0, 1).toUpperCase();
  }

  async function loadPositions() {
    const [p, f] = await Promise.allSettled([api('/api/positions'), api('/api/futures/positions')]);
    state.positions = p.status === 'fulfilled' && Array.isArray(p.value) ? p.value : [];
    state.futuresPositions = f.status === 'fulfilled' && f.value ? f.value : { positions: [] };
  }

  async function loadOrders() {
    try {
      state.orders = await api('/api/orders') || [];
    } catch { state.orders = []; }
  }

  async function loadOptions(code) {
    state.optionCode = code || state.optionCode || (state.futures[0] && state.futures[0].code);
    if (!state.optionCode) { state.options = null; return; }
    try {
      state.options = await api('/api/options?code=' + encodeURIComponent(state.optionCode));
    } catch { state.options = null; }
  }

  function pageHead(title, sub, actions = '') {
    return `<div class="page-head">
      <div><h1 class="page-title">${esc(title)}</h1><div class="page-sub">${sub}</div></div>
      <div class="page-actions">${actions}</div>
    </div>`;
  }

  function kpi(label, value, foot = '', cls = '') {
    return `<div class="card kpi">
      <div class="kpi-label">${esc(label)}</div>
      <div class="kpi-value ${cls}">${value}</div>
      <div class="kpi-foot">${foot}</div>
    </div>`;
  }

  function render() {
    if (state.loading) return;
    const route = state.route;
    try {
      if (route === 'dashboard') renderDashboard();
      else if (route === 'stocks') renderStocks();
      else if (route === 'commodities') renderCommodities();
      else if (route === 'bank') renderBank();
      else if (route === 'futures') renderFutures();
      else if (route === 'options') renderOptions();
      else if (route === 'portfolio') renderPortfolio();
      else if (route === 'orders') renderOrders();
      else if (route === 'macro') renderMacro();
      else if (route === 'companies') renderCompanies();
    } catch (e) {
      app.innerHTML = `<div class="card card-pad"><div class="empty">页面渲染失败：${esc(e.message)}</div></div>`;
    }
  }

  function disposeChart() {
    if (state.chart) { try { state.chart.dispose(); } catch {} state.chart = null; }
  }

  function chartOption(candles, seriesName = '') {
    const dates = candles.map((c, i) => String(c.time ?? i));
    const values = candles.map(c => [Number(c.open), Number(c.close), Number(c.low), Number(c.high)]);
    const ma = (n) => candles.map((c, i) => {
      if (i < n - 1) return null;
      let sum = 0;
      for (let j = i - n + 1; j <= i; j++) sum += Number(candles[j].close);
      return +(sum / n).toFixed(3);
    });
    const line = (field, color, width = 1, zeroNull = false) => ({
      name: field, type: 'line',
      data: candles.map(c => {
        const n = Number(c[field]);
        return (zeroNull && (!Number.isFinite(n) || n === 0)) ? null : (Number.isFinite(n) ? n : null);
      }),
      smooth: true,
      showSymbol: false, lineStyle: { width, color }, connectNulls: false
    });
    const vols = candles.map(c => ({
      value: Number(c.volume) || 0,
      itemStyle: { color: Number(c.close) >= Number(c.open) ? 'rgba(240,68,68,.62)' : 'rgba(18,184,134,.62)' }
    }));
    const macdBars = candles.map(c => ({
      value: Number(c.macdHist) || 0,
      itemStyle: { color: Number(c.macdHist) >= 0 ? '#e65555' : '#22b98c' }
    }));
    const grid = [
      { left: 58, right: 20, top: 14, height: 270 },
      { left: 58, right: 20, top: 300, height: 54 },
      { left: 58, right: 20, top: 374, height: 78 },
      { left: 58, right: 20, top: 474, height: 56 },
      { left: 58, right: 20, top: 548, height: 56 }
    ];
    const xAxis = grid.map((g, i) => ({
      type: 'category', gridIndex: i, data: dates, boundaryGap: false,
      axisLine: { lineStyle: { color: '#263247' } },
      axisTick: { show: false },
      axisLabel: { show: i === 4, color: '#6f7f95', fontSize: 10 },
      splitLine: { show: false }
    }));
    const yAxis = grid.map((g, i) => ({
      gridIndex: i, scale: true, splitLine: { lineStyle: { color: 'rgba(38,50,71,.42)' } },
      axisLabel: { color: '#6f7f95', fontSize: 9 },
      axisLine: { lineStyle: { color: '#263247' } }
    }));
    const markLine = (value, color) => ({
      silent: true, symbol: 'none', label: { show: false },
      lineStyle: { color, type: 'dashed', width: 1 },
      data: [{ yAxis: value }]
    });
    return {
      animation: false,
      backgroundColor: 'transparent',
      grid,
      tooltip: {
        trigger: 'axis', axisPointer: { type: 'cross' },
        backgroundColor: '#0d131d', borderColor: '#2b3850',
        textStyle: { color: '#dce6f4', fontSize: 11 }
      },
      axisPointer: { link: [{ xAxisIndex: 'all' }] },
      graphic: [
        { type: 'text', left: 58, top: 286, style: { text: 'VOL', fill: '#65768e', fontSize: 10 } },
        { type: 'text', left: 58, top: 358, style: { text: 'MACD(12,26,9)', fill: '#65768e', fontSize: 10 } },
        { type: 'text', left: 58, top: 458, style: { text: 'RSI(14)', fill: '#65768e', fontSize: 10 } },
        { type: 'text', left: 58, top: 532, style: { text: 'KDJ(9,3,3)', fill: '#65768e', fontSize: 10 } }
      ],
      dataZoom: [{ type: 'inside', xAxisIndex: [0, 1, 2, 3, 4], start: 0, end: 100 }],
      xAxis,
      yAxis,
      series: [
        { name: seriesName || 'K线', type: 'candlestick', data: values, itemStyle: { color: '#f04444', color0: '#12b886', borderColor: '#f04444', borderColor0: '#12b886' } },
        { ...line('ma5', '#f5a623', 1, true), name: 'MA5' },
        { ...line('ma10', '#4c8dff', 1, true), name: 'MA10' },
        { ...line('ma20', '#a78bfa', 1, true), name: 'MA20' },
        { ...line('bollUp', '#8b9bb4', 1, true), name: 'BOLL上', lineStyle: { width: 1, color: '#8b9bb4', type: 'dashed' } },
        { ...line('bollMid', '#8b9bb4', 1, true), name: 'BOLL中', lineStyle: { width: 1, color: '#8b9bb4', type: 'dashed', opacity: .7 } },
        { ...line('bollLow', '#8b9bb4', 1, true), name: 'BOLL下', lineStyle: { width: 1, color: '#8b9bb4', type: 'dashed' } },
        { name: '成交量', type: 'bar', xAxisIndex: 1, yAxisIndex: 1, data: vols, barWidth: '55%' },
        { name: 'MACD柱', type: 'bar', xAxisIndex: 2, yAxisIndex: 2, data: macdBars, barWidth: '55%' },
        { ...line('macdDif', '#58a6ff', 1), name: 'DIF', xAxisIndex: 2, yAxisIndex: 2 },
        { ...line('macdDea', '#f5a623', 1), name: 'DEA', xAxisIndex: 2, yAxisIndex: 2 },
        { ...line('rsi', '#58a6ff', 1.2), name: 'RSI', xAxisIndex: 3, yAxisIndex: 3, markLine: { silent: true, symbol: 'none', label: { show: false }, lineStyle: { color: '#7d8ba0', type: 'dashed', width: 1 }, data: [{ yAxis: 70 }, { yAxis: 30 }] } },
        { ...line('kdjK', '#58a6ff', 1), name: 'K', xAxisIndex: 4, yAxisIndex: 4, markLine: markLine(80, '#e65555') },
        { ...line('kdjD', '#f5a623', 1), name: 'D', xAxisIndex: 4, yAxisIndex: 4 },
        { ...line('kdjJ', '#a78bfa', 1), name: 'J', xAxisIndex: 4, yAxisIndex: 4, markLine: markLine(20, '#22b98c') }
      ]
    };
  }

  function mountChart(el, option) {
    if (!window.echarts || !el) return;
    disposeChart();
    state.chart = echarts.init(el, null, { renderer: 'canvas' });
    state.chart.setOption(option);
  }

  async function renderStockChart(stockId, type = state.stockPeriod || 4) {
    const el = $('#stockChart');
    if (!el) return;
    let candles = [];
    try {
      const res = await api('/api/kline?asset=' + encodeURIComponent(stockId) + '&type=' + Number(type || 4));
      candles = Array.isArray(res.klines) ? res.klines : [];
    } catch {}
    if (state.selectedStock !== stockId) return;
    if (!candles.length) {
      const s = state.stocks.find(x => x.stockId === stockId);
      const arr = s && s.sparkline ? s.sparkline : [];
      if (!arr.length) {
        disposeChart();
        el.innerHTML = '<div class="empty">暂无K线数据（等待玩家/引擎初始化）</div>';
        return;
      }
      mountChart(el, {
        animation: false, backgroundColor: 'transparent', grid: { left: 40, right: 12, top: 16, bottom: 24 },
        xAxis: { type: 'category', data: arr.map((_, i) => i + 1), axisLabel: { color: '#6f7f95', fontSize: 10 }, axisLine: { lineStyle: { color: '#263247' } } },
        yAxis: { type: 'value', scale: true, axisLabel: { color: '#6f7f95', fontSize: 10 }, splitLine: { lineStyle: { color: 'rgba(38,50,71,.5)' } } },
        series: [{ type: 'line', data: arr, smooth: true, showSymbol: false, lineStyle: { color: '#4c8dff', width: 2 }, areaStyle: { color: 'rgba(76,141,255,.12)' } }]
      });
      return;
    }
    mountChart(el, chartOption(candles.slice(-120), stockId));
  }

  function renderDashboard() {
    const s = state.stocks;
    const movers = [...s].sort((a, b) => Number(b.change) - Number(a.change));
    const top = movers.slice(0, 6);
    const bottom = movers.slice(-6).reverse();
    const f = state.futures.slice(0, 6);
    const futEq = Number(state.futuresPositions.equity || 0);
    const macro = state.macro || {};
    app.innerHTML = pageHead('市场总览', `交易终端 · ${new Date().toLocaleDateString('zh-CN')} · ${state.account.playerName || '—'}`,
      `<button class="btn ghost" id="goStocks">打开股票</button><button class="btn primary" id="goFutures">CZCE 期货</button>`) +
      `<div class="grid g4">
        ${kpi('总资产', fmtMoney(state.account.totalAssets), `<span class="chip">现金 ${fmtMoney(state.account.cash)}</span>`)}
        ${kpi('持仓市值', fmtMoney(state.account.totalMarketValue), `<span class="chip">${state.positions.length} 个持仓</span>`)}
        ${kpi('Kronos 指数', fmtNum(state.index.value), `<span class="${sign(state.index.change)}">${signed(state.index.change)}%</span>`)}
        ${kpi('CZCE 权益', fmtMoney(futEq), `<span class="chip">保证金 ${fmtMoney(state.futuresPositions.frozenMargin)}</span>`)}
      </div>
      <div class="grid g-main" style="margin-top:14px">
        <div class="card">
          <div class="card-head"><div><div class="card-title">市场基准</div><div class="card-sub">全市场等权走势 / 最近 30 个采样</div></div>
            <div class="legend"><span><i style="background:#4c8dff"></i>等权</span></div></div>
          <div id="dashChart" class="chart" style="height:310px"></div>
        </div>
        <div class="card">
          <div class="card-head"><div><div class="card-title">涨跌幅榜</div><div class="card-sub">按当日涨跌排序</div></div><a class="btn sm ghost" href="#/stocks">全部</a></div>
          <div class="table-wrap"><table><thead><tr><th>标的</th><th>现价</th><th>涨跌</th></tr></thead><tbody>
            ${top.map(x => `<tr data-stock="${esc(x.stockId)}"><td><div class="sym"><b>${esc(x.name)}</b><span>${esc(x.code || x.stockId)}</span></div></td><td>${fmtNum(x.price)}</td><td>${pctChange(x.change)}</td></tr>`).join('') || emptyRow(3)}
          </tbody></table></div>
        </div>
      </div>
      <div class="grid g2" style="margin-top:14px">
        <div class="card">
          <div class="card-head"><div><div class="card-title">CZCE 期货</div><div class="card-sub">主力连续 / 当前报价</div></div><a class="btn sm ghost" href="#/futures">交易</a></div>
          <div class="table-wrap"><table><thead><tr><th>合约</th><th>最新</th><th>涨跌</th><th>持仓</th></tr></thead><tbody>
            ${f.map(x => `<tr data-future="${esc(x.code)}"><td><div class="sym"><b>${esc(x.code)}</b><span>${esc(x.name || x.underlying)}</span></div></td><td>${fmtNum(x.lastPrice)}</td><td>${pctChange(x.change)}</td><td>${fmtInt(x.openInterest)}</td></tr>`).join('') || emptyRow(4)}
          </tbody></table></div>
        </div>
        <div class="card">
          <div class="card-head"><div><div class="card-title">宏观快照</div><div class="card-sub">利率 / 货币 / 风险</div></div><a class="btn sm ghost" href="#/macro">详情</a></div>
          <div class="card-pad list">
            ${listRow('贴现率', macro.discountRate, '%')}
            ${listRow('存款准备金率', macro.reserveRequirement, '%')}
            ${listRow('M2', macro.m2, '')}
            ${listRow('不良率', macro.nplRatio, '%')}
            ${listRow('信用增速', macro.creditGrowth, '%')}
          </div>
        </div>
      </div>
      <div class="card" style="margin-top:14px">
        <div class="card-head"><div><div class="card-title">跌幅榜</div><div class="card-sub">风险偏好与资金流出</div></div></div>
        <div class="table-wrap"><table><thead><tr><th>标的</th><th>现价</th><th>涨跌</th><th>成交额</th><th>走势</th></tr></thead><tbody>
          ${bottom.map(x => `<tr data-stock="${esc(x.stockId)}"><td><div class="sym"><b>${esc(x.name)}</b><span>${esc(x.code || x.stockId)}</span></div></td><td>${fmtNum(x.price)}</td><td>${pctChange(x.change)}</td><td>${fmtMoney(x.amount)}</td><td>${sparkline(x.sparkline, Number(x.change) >= 0)}</td></tr>`).join('') || emptyRow(5)}
        </tbody></table></div>
      </div>`;

    $('#goStocks').onclick = () => setRoute('stocks');
    $('#goFutures').onclick = () => setRoute('futures');
    app.querySelectorAll('[data-stock]').forEach(el => el.onclick = () => {
      state.selectedStock = el.dataset.stock; setRoute('stocks');
    });
    app.querySelectorAll('[data-future]').forEach(el => el.onclick = () => {
      state.selectedFuture = el.dataset.future; setRoute('futures');
    });
    const basket = averageSparkline(state.stocks);
    const dashChart = $('#dashChart');
    if (!basket.length) {
      disposeChart();
      dashChart.innerHTML = '<div class="empty">暂无市场数据（等待玩家/引擎初始化）</div>';
    } else {
      mountChart(dashChart, {
      animation: false, backgroundColor: 'transparent', grid: { left: 44, right: 18, top: 16, bottom: 26 },
      tooltip: { trigger: 'axis', backgroundColor: '#0d131d', borderColor: '#2b3850', textStyle: { color: '#dce6f4', fontSize: 11 } },
      xAxis: { type: 'category', data: basket.map((_, i) => i + 1), boundaryGap: false, axisLabel: { color: '#6f7f95', fontSize: 10 }, axisLine: { lineStyle: { color: '#263247' } } },
      yAxis: { type: 'value', scale: true, axisLabel: { color: '#6f7f95', fontSize: 10 }, splitLine: { lineStyle: { color: 'rgba(38,50,71,.5)' } } },
      series: [{ type: 'line', data: basket, smooth: true, showSymbol: false, lineStyle: { color: '#4c8dff', width: 2 }, areaStyle: { color: 'rgba(76,141,255,.12)' } }]
      });
    }
  }

  function averageSparkline(stocks) {
    const series = stocks.map(s => s.sparkline || []).filter(a => a.length > 1);
    if (!series.length) return [];
    const len = Math.min(...series.map(a => a.length));
    const out = [];
    for (let i = 0; i < len; i++) {
      let sum = 0;
      for (const a of series) sum += Number(a[a.length - len + i]);
      out.push(+(sum / series.length).toFixed(3));
    }
    return out;
  }

  function listRow(label, value, unit = '') {
    const n = Number(value);
    return `<div class="list-row"><span>${esc(label)}</span><b class="mono">${Number.isFinite(n) ? fmtNum(n) : '—'}${unit}</b></div>`;
  }

  function renderStocks() {
    const q = state.search.trim().toLowerCase();
    const list = state.stocks.filter(s => !q || String(s.stockId).toLowerCase().includes(q) || String(s.name).toLowerCase().includes(q) || String(s.code || '').toLowerCase().includes(q));
    if (!state.selectedStock || !state.stocks.some(s => s.stockId === state.selectedStock)) {
      state.selectedStock = list[0] ? list[0].stockId : (state.stocks[0] && state.stocks[0].stockId);
    }
    const sel = state.stocks.find(s => s.stockId === state.selectedStock);
    app.innerHTML = pageHead('股票市场', `${state.stocks.length} 个标的 · 点击行查看K线与交易`,
      `<span class="chip">现金 ${fmtMoney(state.account.cash)}</span>`) +
      `<div class="grid" style="grid-template-columns:minmax(0,.86fr) minmax(0,1.14fr)">
        <div class="card">
          <div class="card-head"><div><div class="card-title">行情列表</div><div class="card-sub">A股 / 实时报价</div></div>
            <div class="legend"><span><i style="background:var(--red)"></i>涨</span><span><i style="background:var(--green)"></i>跌</span></div></div>
          <div class="table-wrap" style="max-height:calc(100vh - 210px)">
            <table><thead><tr><th>名称 / 代码</th><th>现价</th><th>涨跌</th><th>成交额</th><th>走势</th></tr></thead><tbody>
              ${list.map(s => `<tr data-stock="${esc(s.stockId)}" class="${s.stockId === state.selectedStock ? 'sel' : ''}">
                <td><div class="sym"><b>${esc(s.name)}</b><span>${esc(s.code || s.stockId)}</span></div></td>
                <td>${fmtNum(s.price)}</td>
                <td>${pctChange(s.change)}</td>
                <td>${fmtMoney(s.amount)}</td>
                <td>${sparkline(s.sparkline, Number(s.change) >= 0)}</td>
              </tr>`).join('') || emptyRow(5, '没有匹配的股票')}
            </tbody></table>
          </div>
        </div>
        <div class="card">
          ${sel ? `<div class="detail-head">
            <div><div class="detail-title">${esc(sel.name)}</div><div class="detail-code">${esc(sel.code || sel.stockId)} · ${esc(sel.industry || 'A股')}</div></div>
            <div class="right"><div class="detail-price">${fmtNum(sel.price)}</div><div class="detail-change ${sign(sel.change)}">${signed(sel.change)}%</div></div>
          </div>
          <div class="card-pad" style="padding-top:10px;padding-bottom:8px;border-top:1px solid var(--line)">
            <div class="toolbar">
              <div class="seg" id="stockPeriod">
                <button data-period="0">分时</button>
                <button data-period="4" class="on">日K</button>
                <button data-period="5">月K</button>
              </div>
              <div class="legend"><span><i style="background:#f5a623"></i>MA5</span><span><i style="background:#4c8dff"></i>MA10</span><span><i style="background:#a78bfa"></i>MA20</span><span><i style="background:#8b9bb4"></i>BOLL(20,2)</span></div>
            </div>
          </div>
          <div id="stockChart" class="chart" style="height:620px"></div>
          <div class="card-pad" style="border-top:1px solid var(--line)">
            <div class="order-side" id="stockSide"><button class="on-buy" data-side="buy">买入</button><button data-side="sell">卖出</button></div>
            <div class="form" style="margin-top:10px">
              <div class="grid g2" style="gap:9px">
                <div class="field"><label>价格</label><input id="stockPrice" type="number" step="0.01" value="${Number(sel.price || 0).toFixed(2)}"></div>
                <div class="field"><label>数量（股）</label><input id="stockQty" type="number" step="100" min="100" value="100"></div>
              </div>
              <div class="quick"><button data-qty="100">100</button><button data-qty="500">500</button><button data-qty="1000">1000</button><button data-qty="5000">5000</button></div>
              <div class="order-summary"><div>预估金额 <b id="stockAmount">—</b></div><div>可用现金 <b>${fmtMoney(state.account.cash)}</b></div></div>
              <button class="btn buy" id="stockSubmit" style="height:40px">买入 ${esc(sel.name)}</button>
            </div>
          </div>` : '<div class="empty">暂无股票</div>'}
        </div>
      </div>`;
    app.querySelectorAll('[data-stock]').forEach(el => el.onclick = () => {
      state.selectedStock = el.dataset.stock; renderStocks();
    });
    if (sel) {
      state.stockSide = state.stockSide || 'buy';
      bindStockOrder(sel);
      $$('#stockPeriod button').forEach(btn => {
        btn.classList.toggle('on', Number(btn.dataset.period) === Number(state.stockPeriod));
        btn.onclick = () => {
          state.stockPeriod = Number(btn.dataset.period);
          $$('#stockPeriod button').forEach(b => b.classList.toggle('on', b === btn));
          renderStockChart(sel.stockId, state.stockPeriod);
        };
      });
      renderStockChart(sel.stockId, state.stockPeriod);
    }
  }

  function bindStockOrder(sel) {
    const price = $('#stockPrice'), qty = $('#stockQty'), amount = $('#stockAmount'), submit = $('#stockSubmit');
    const update = () => {
      const p = Number(price.value) || 0, q = Number(qty.value) || 0;
      if (amount) amount.textContent = fmtMoney(p * q);
    };
    price?.addEventListener('input', update);
    qty?.addEventListener('input', update);
    update();
    $$('#stockSide button').forEach(btn => btn.onclick = () => {
      state.stockSide = btn.dataset.side;
      $$('#stockSide button').forEach(b => b.className = b.dataset.side === state.stockSide ? 'on-' + state.stockSide : '');
      submit.className = 'btn ' + (state.stockSide === 'buy' ? 'buy' : 'sell');
      submit.textContent = (state.stockSide === 'buy' ? '买入 ' : '卖出 ') + sel.name;
    });
    app.querySelectorAll('[data-qty]').forEach(b => b.onclick = () => { qty.value = b.dataset.qty; update(); });
    submit.onclick = async () => {
      const p = Number(price.value) || 0, q = Math.floor(Number(qty.value) || 0);
      if (!q || q <= 0) return toast('数量必须为正整数', true);
      submit.disabled = true;
      try {
        const res = await api('/api/order', {
          method: 'POST',
          body: JSON.stringify({ assetId: sel.stockId, side: state.stockSide, type: p > 0 ? 'limit' : 'market', price: p, quantity: q })
        });
        toast(res && res.success ? (res.message || '下单已提交') : (res.message || '下单失败'), !(res && res.success));
        if (res && res.success) loadCore().then(render);
      } catch (e) { toast(e.message, true); } finally { submit.disabled = false; }
    };
  }

  function renderFutures() {
    const q = state.search.trim().toLowerCase();
    const list = state.futures.filter(f => !q || f.code.toLowerCase().includes(q) || String(f.name || '').toLowerCase().includes(q) || String(f.underlying || '').toLowerCase().includes(q));
    if (!state.selectedFuture || !state.futures.some(f => f.code === state.selectedFuture)) {
      state.selectedFuture = list[0] ? list[0].code : (state.futures[0] && state.futures[0].code);
    }
    const sel = state.futures.find(f => f.code === state.selectedFuture);
    app.innerHTML = pageHead('CZCE 期货', `${state.futures.length} 个合约 · 保证金 / 涨跌停 / 封板`,
      `<span class="chip">${state.czce.sessionOpen ? '交易中' : '休市'}</span><span class="chip">实物交割 ${fmtInt(state.czce.physicalDeliveryQuantity)} 件 / ${fmtInt(state.czce.physicalDeliveryLots)} 手</span><span class="chip">账户 ${fmtMoney(state.futuresPositions.equity)}</span>`) +
      `<div class="grid" style="grid-template-columns:minmax(0,1.3fr) minmax(340px,.7fr)">
        <div class="card">
          <div class="card-head"><div><div class="card-title">合约行情</div><div class="card-sub">CZCE 主力连续</div></div></div>
          <div class="table-wrap" style="max-height:calc(100vh - 210px)"><table>
            <thead><tr><th>合约</th><th>最新</th><th>涨跌</th><th>涨停</th><th>跌停</th><th>保证金</th><th>持仓</th></tr></thead><tbody>
            ${list.map(f => `<tr data-future="${esc(f.code)}" class="${f.code === state.selectedFuture ? 'sel' : ''}">
              <td><div class="sym"><b>${esc(f.code)}</b><span>${esc(f.name || f.underlying)}</span></div></td>
              <td>${fmtNum(f.lastPrice)}</td><td>${pctChange(f.change)}</td>
              <td class="up">${fmtNum(f.limitUp)}</td><td class="down">${fmtNum(f.limitDown)}</td>
              <td>${fmtNum(f.marginRatio)}%</td><td>${fmtInt(f.openInterest)}</td>
            </tr>`).join('') || emptyRow(7, '没有匹配的合约')}
          </tbody></table></div>
        </div>
        <div class="card">
          ${sel ? `<div class="detail-head"><div><div class="detail-title">${esc(sel.code)}</div><div class="detail-code">${esc(sel.name || sel.underlying)} · 乘数 ${fmtInt(sel.multiplier)}</div></div>
            <div class="right"><div class="detail-price">${fmtNum(sel.lastPrice)}</div><div class="detail-change ${sign(sel.change)}">${signed(sel.change)}%</div></div></div>
          <div class="card-pad" style="border-top:1px solid var(--line)">
            <div class="list">
              <div class="list-row"><span>昨结</span><b>${fmtNum(sel.prevSettlePrice)}</b></div>
              <div class="list-row"><span>涨停 / 跌停</span><b><span class="down">${fmtNum(sel.limitUp)}</span> / <span class="up">${fmtNum(sel.limitDown)}</span></b></div>
              <div class="list-row"><span>保证金率</span><b>${fmtNum(sel.marginRatio)}%</b></div>
              <div class="list-row"><span>持仓量</span><b>${fmtInt(sel.openInterest)}</b></div>
            </div>
            <div class="order-side" id="futureSide" style="margin-top:13px"><button class="on-buy" data-side="long">做多</button><button data-side="short">做空</button></div>
            <div class="order-side" id="futureAction" style="margin-top:7px"><button class="on-buy" data-action="open">开仓</button><button data-action="close">平仓</button></div>
            <div class="form" style="margin-top:10px">
              <div class="field"><label>手数</label><input id="futureLots" type="number" min="1" step="1" value="1"></div>
              <div class="quick"><button data-lots="1">1</button><button data-lots="2">2</button><button data-lots="5">5</button><button data-lots="10">10</button></div>
              <div class="order-summary"><div>合约乘数 <b>${fmtInt(sel.multiplier)}</b></div><div>一手名义价值 <b>${fmtMoney(Number(sel.lastPrice) * Number(sel.multiplier))}</b></div></div>
              <button class="btn buy" id="futureSubmit" style="height:40px">做多开仓</button>
            </div>
          </div>` : '<div class="empty">暂无合约</div>'}
        </div>
      </div>
      <div class="card" style="margin-top:14px">
        <div class="card-head"><div><div class="card-title">期货持仓</div><div class="card-sub">保证金账户</div></div><span class="chip">可用 ${fmtMoney(state.futuresPositions.freeCash)}</span></div>
        <div class="table-wrap"><table><thead><tr><th>合约</th><th>方向</th><th>手数</th><th>开仓均价</th><th>最新</th><th>浮动盈亏</th><th>保证金</th></tr></thead><tbody>
          ${(state.futuresPositions.positions || []).map(p => `<tr><td>${esc(p.code)}</td><td class="${p.side === 'long' ? 'up' : 'down'}">${p.side === 'long' ? '多' : '空'}</td><td>${fmtInt(p.lots)}</td><td>${fmtNum(p.openPrice)}</td><td>${fmtNum(p.lastPrice)}</td><td class="${sign(p.floatPnl)}">${signed(p.floatPnl)}</td><td>${fmtMoney(p.usedMargin)}</td></tr>`).join('') || emptyRow(7, '暂无期货持仓')}
        </tbody></table></div>
      </div>`;
    app.querySelectorAll('[data-future]').forEach(el => el.onclick = () => {
      state.selectedFuture = el.dataset.future; renderFutures();
    });
    if (sel) bindFutureOrder(sel);
  }

  function bindFutureOrder(sel) {
    state.futureSide = state.futureSide || 'long';
    state.futureAction = state.futureAction || 'open';
    const lots = $('#futureLots'), submit = $('#futureSubmit');
    const paint = () => {
      $$('#futureSide button').forEach(b => b.className = b.dataset.side === state.futureSide ? 'on-' + (b.dataset.side === 'long' ? 'buy' : 'sell') : '');
      $$('#futureAction button').forEach(b => b.className = b.dataset.action === state.futureAction ? 'on-buy' : '');
      submit.className = 'btn ' + (state.futureSide === 'long' ? 'buy' : 'sell');
      submit.textContent = (state.futureSide === 'long' ? '做多' : '做空') + (state.futureAction === 'open' ? '开仓' : '平仓');
    };
    $$('#futureSide button').forEach(b => b.onclick = () => { state.futureSide = b.dataset.side; paint(); });
    $$('#futureAction button').forEach(b => b.onclick = () => { state.futureAction = b.dataset.action; paint(); });
    app.querySelectorAll('[data-lots]').forEach(b => b.onclick = () => { lots.value = b.dataset.lots; });
    paint();
    submit.onclick = async () => {
      const n = Math.floor(Number(lots.value) || 0);
      if (!n) return toast('手数必须为正', true);
      submit.disabled = true;
      try {
        const res = await api('/api/futures/order', {
          method: 'POST',
          body: JSON.stringify({ code: sel.code, side: state.futureSide, action: state.futureAction, lots: n })
        });
        toast(res && res.success ? (res.message || '期货委托已提交') : (res.message || '委托失败'), !(res && res.success));
        if (res && res.success) {
          const [p] = await Promise.allSettled([api('/api/futures/positions')]);
          if (p.status === 'fulfilled' && p.value) state.futuresPositions = p.value;
          renderFutures();
        }
      } catch (e) { toast(e.message, true); } finally { submit.disabled = false; }
    };
  }

  async function renderOptions() {
    await loadOptions(state.optionCode);
    const data = state.options || {};
    const contracts = Array.isArray(data.contracts) ? data.contracts : [];
    const codes = state.futures.map(f => f.code);
    const expiries = [...new Set(contracts.map(c => Number(c.expiryDays)))].sort((a, b) => a - b);
    const expiry = expiries.includes(Number(state.optionExpiry)) ? Number(state.optionExpiry) : (expiries[0] || 60);
    const rows = {};
    for (const c of contracts) {
      if (Number(c.expiryDays) !== expiry) continue;
      const k = String(Number(c.strike).toFixed(2));
      if (!rows[k]) rows[k] = { strike: Number(c.strike) };
      if (c.type === 'CALL') rows[k].call = c;
      else rows[k].put = c;
    }
    const strikes = Object.values(rows).sort((a, b) => a.strike - b.strike);
    app.innerHTML = pageHead('CZCE 期权', 'SABR 波动率曲面 · Black-76 定价 · 30/60/90 天',
      `<span class="chip">标的 ${esc(data.underlying || state.optionCode || '—')}</span><span class="chip">F ${fmtNum(data.forward)}</span>`) +
      `<div class="card" style="margin-bottom:14px">
        <div class="card-pad opt-head">
          <div class="field" style="min-width:230px"><label>标的合约</label><select id="optCode">${codes.map(c => `<option ${c === state.optionCode ? 'selected' : ''}>${esc(c)}</option>`).join('')}</select></div>
          <div class="field" style="min-width:150px"><label>到期</label><select id="optExpiry">${expiries.map(d => `<option value="${d}" ${d === expiry ? 'selected' : ''}>${d} 天</option>`).join('')}</select></div>
          <div class="page-sub" style="margin-left:auto">SABR 参数由期权台每日更新；链为只读报价</div>
        </div>
      </div>
      <div class="card">
        <div class="card-head"><div><div class="card-title">期权链</div><div class="card-sub">Call / Put · 5 档行权价</div></div>
          <div class="legend"><span class="opt-call"><i style="background:#ff8b8b"></i>Call</span><span class="opt-put"><i style="background:#67d9b8"></i>Put</span></div></div>
        <div class="table-wrap"><table><thead><tr>
          <th>Call IV</th><th>Call 价格</th><th>Delta</th><th>Vega</th><th>行权价</th><th>Put 价格</th><th>Put IV</th><th>Delta</th><th>Vega</th>
        </tr></thead><tbody>
          ${strikes.map(r => `<tr>
            <td class="opt-call">${r.call ? fmtNum(r.call.iv) + '%' : '—'}</td><td>${r.call ? fmtNum(r.call.price) : '—'}</td><td>${r.call ? fmtNum(r.call.delta, 3) : '—'}</td><td>${r.call ? fmtNum(r.call.vega, 3) : '—'}</td>
            <td style="font-weight:700">${fmtNum(r.strike)}</td>
            <td class="opt-put">${r.put ? fmtNum(r.put.price) : '—'}</td><td class="opt-put">${r.put ? fmtNum(r.put.iv) + '%' : '—'}</td><td>${r.put ? fmtNum(r.put.delta, 3) : '—'}</td><td>${r.put ? fmtNum(r.put.vega, 3) : '—'}</td>
          </tr>`).join('') || emptyRow(9, '暂无期权链')}
        </tbody></table></div>
      </div>
      <div class="grid g3" style="margin-top:14px">
        <div class="card kpi"><div class="kpi-label">ATM IV</div><div class="kpi-value">${atmIv(contracts, expiry)}</div><div class="kpi-foot">SABR 曲面中间</div></div>
        <div class="card kpi"><div class="kpi-label">期限结构</div><div class="kpi-value">${expiries.join(' / ')}</div><div class="kpi-foot">天</div></div>
        <div class="card kpi"><div class="kpi-label">定价引擎</div><div class="kpi-value" style="font-size:18px">finmath · SABR</div><div class="kpi-foot">Black-76 IV→价格</div></div>
      </div>`;
    $('#optCode').onchange = e => { state.optionCode = e.target.value; state.options = null; renderOptions(); };
    $('#optExpiry').onchange = e => { state.optionExpiry = Number(e.target.value); renderOptions(); };
  }

  function atmIv(contracts, expiry) {
    const calls = contracts.filter(c => c.type === 'CALL' && Number(c.expiryDays) === expiry);
    if (!calls.length) return '—';
    const avg = calls.reduce((a, c) => a + Number(c.iv || 0), 0) / calls.length;
    return fmtNum(avg) + '%';
  }

  async function renderPortfolio() {
    await loadPositions();
    const fp = state.futuresPositions || {};
    const stockPnl = state.positions.reduce((a, p) => a + Number(p.profit || 0), 0);
    app.innerHTML = pageHead('持仓与账户', `${state.account.playerName || '—'} · 股票与期货统一视图`,
      `<span class="chip">总资产 ${fmtMoney(state.account.totalAssets)}</span>`) +
      `<div class="grid g4">
        ${kpi('现金', fmtMoney(state.account.cash))}
        ${kpi('股票市值', fmtMoney(state.account.totalMarketValue), `<span class="${sign(stockPnl)}">${signed(stockPnl)}</span>`)}
        ${kpi('期货权益', fmtMoney(fp.equity), `<span class="chip">保证金 ${fmtMoney(fp.frozenMargin)}</span>`)}
        ${kpi('持仓数', String((state.positions || []).length + (fp.positions || []).length), '<span class="chip">股票 + 期货</span>')}
      </div>
      <div class="card" style="margin-top:14px">
        <div class="card-head"><div><div class="card-title">股票持仓</div><div class="card-sub">成本 / 现价 / 浮动盈亏</div></div></div>
        <div class="table-wrap"><table><thead><tr><th>标的</th><th>数量</th><th>可用</th><th>成本</th><th>现价</th><th>市值</th><th>盈亏</th><th>盈亏率</th></tr></thead><tbody>
          ${state.positions.map(p => `<tr><td><div class="sym"><b>${esc(p.name)}</b><span>${esc(p.assetId)}</span></div></td><td>${fmtInt(p.quantity)}</td><td>${fmtInt(p.available)}</td><td>${fmtNum(p.avgCost)}</td><td>${fmtNum(p.lastPrice)}</td><td>${fmtMoney(p.marketValue)}</td><td class="${sign(p.profit)}">${signed(p.profit)}</td><td class="${sign(p.profitPct)}">${signed(p.profitPct)}%</td></tr>`).join('') || emptyRow(8, '暂无股票持仓')}
        </tbody></table></div>
      </div>
      <div class="card" style="margin-top:14px">
        <div class="card-head"><div><div class="card-title">期货持仓</div><div class="card-sub">CZCE 保证金账户</div></div><span class="chip">可用 ${fmtMoney(fp.freeCash)}</span></div>
        <div class="table-wrap"><table><thead><tr><th>合约</th><th>方向</th><th>手数</th><th>开仓均价</th><th>最新</th><th>浮动盈亏</th><th>保证金</th></tr></thead><tbody>
          ${(fp.positions || []).map(p => `<tr><td>${esc(p.code)}</td><td class="${p.side === 'long' ? 'up' : 'down'}">${p.side === 'long' ? '多' : '空'}</td><td>${fmtInt(p.lots)}</td><td>${fmtNum(p.openPrice)}</td><td>${fmtNum(p.lastPrice)}</td><td class="${sign(p.floatPnl)}">${signed(p.floatPnl)}</td><td>${fmtMoney(p.usedMargin)}</td></tr>`).join('') || emptyRow(7, '暂无期货持仓')}
        </tbody></table></div>
      </div>`;
  }

  async function renderOrders() {
    await loadOrders();
    app.innerHTML = pageHead('委托订单', '未完成 / 部分成交 / 已成交',
      `<span class="chip">${state.orders.length} 条</span>`) +
      `<div class="card"><div class="table-wrap"><table><thead><tr><th>标的</th><th>方向</th><th>类型</th><th>价格</th><th>数量</th><th>剩余</th><th>状态</th><th>操作</th></tr></thead><tbody>
        ${state.orders.map(o => `<tr><td><div class="sym"><b>${esc(o.name)}</b><span>${esc(o.assetId)}</span></div></td><td class="${o.side === 'buy' ? 'up' : 'down'}">${o.side === 'buy' ? '买入' : '卖出'}</td><td>${o.type === 'market' ? '市价' : '限价'}</td><td>${fmtNum(o.price)}</td><td>${fmtInt(o.quantity)}</td><td>${fmtInt(o.remaining)}</td><td><span class="chip">${esc(o.status)}</span></td><td>${o.status === 'open' || o.status === 'partial' ? `<button class="btn sm ghost" data-cancel="${esc(o.orderId)}">撤单</button>` : '—'}</td></tr>`).join('') || emptyRow(8, '暂无委托')}
      </tbody></table></div></div>`;
    app.querySelectorAll('[data-cancel]').forEach(btn => btn.onclick = async (e) => {
      e.stopPropagation();
      try {
        const res = await api('/api/cancelOrder', { method: 'POST', body: JSON.stringify({ orderId: btn.dataset.cancel }) });
        toast(res && res.success ? (res.message || '撤单成功') : (res.message || '撤单失败'), !(res && res.success));
        renderOrders();
      } catch (err) { toast(err.message, true); }
    });
  }

  function renderMacro() {
    const m = state.macro || {};
    const w = state.worldmacro || {};
    const c = state.crossmacro || {};
    const ak = state.ak || {};
    const cd = ak.cobbDouglas || {};
    const sectors = c.sectorTilts || {};
    app.innerHTML = pageHead('宏观与风险', '世界 → 股票 / 期货 / 期权 / 玩家 的跨资产传导',
      `<span class="chip">${w.regime || (Number(w.eventRisk) > .6 ? 'RISK' : 'NORMAL')}</span>`) +
      `<div class="grid g4">
        ${kpi('贴现率', fmtNum(m.discountRate) + '%')}
        ${kpi('准备金率', fmtNum(m.reserveRequirement) + '%')}
        ${kpi('M2', fmtMoney(m.m2))}
        ${kpi('不良率', fmtNum(m.nplRatio) + '%')}
      </div>
      <div class="grid g4" style="margin-top:14px">
        ${kpi('股票全局 Tilt', signed(Number(c.stockGlobalTilt || 0) * 100) + '%', `<span class="chip">风险偏好 ${fmtNum(Number(c.riskAppetite || 0) * 100)}%</span>`)}
        ${kpi('期货 Spot 乘数', fmtNum(c.futuresSpotMultiplier) + '×', `<span class="chip">商品代理 ${signed(Number(c.commodityReturn || 0) * 100)}%</span>`)}
        ${kpi('期权 Vol 乘数', fmtNum(c.optionVolMultiplier) + '×', `<span class="chip">Skew ${fmtNum(Number(c.optionSkewShift || 0) * 100)}%</span>`)}
        ${kpi('玩家净流', signed(Number(c.playerStockFlow || 0) * 100), `<span class="chip">期货流 ${signed(Number(c.playerFuturesFlow || 0) * 100)}</span>`)}
      </div>
      <div class="grid g4" style="margin-top:14px">
        ${kpi('AK 资本', fmtMoney(ak.capital))}
        ${kpi('AK 产出', fmtMoney(ak.output), `<span class="chip">TFP ${fmtNum(ak.tfp)}</span>`)}
        ${kpi('AK 增长', signed(Number(ak.growth || 0) * 100) + '%', `<span class="chip">储蓄率 ${fmtNum(Number(ak.savingsRate || 0) * 100)}%</span>`)}
        ${kpi('AK 资本回报', signed(Number(ak.returnRate || 0) * 100) + '%', `<span class="chip">投资 ${fmtMoney(ak.investment)}</span>`)}
      </div>
      <div class="grid g4" style="margin-top:14px">
        ${kpi('CD 产出', fmtMoney(cd.output), `<span class="chip">α ${fmtNum(Number(cd.capitalShare || 0) * 100)}%</span>`)}
        ${kpi('CD TFP', fmtNum(cd.tfp), `<span class="chip">MPK ${fmtNum(cd.mpk)}</span>`)}
        ${kpi('CD 劳动边际产出', fmtNum(cd.mpl), `<span class="chip">MPL</span>`)}
        ${kpi('资本缺口', signed(Number(cd.capitalGap || 0) * 100) + '%', `<span class="chip">最优 ${fmtMoney(cd.optimalCapital)}</span>`)}
      </div>
      <div class="grid g2" style="margin-top:14px">
        <div class="card"><div class="card-head"><div><div class="card-title">跨资产传导</div><div class="card-sub">Cross-Asset Macro</div></div></div><div class="card-pad list">
          ${listRow('股票收益', Number(c.stockReturn || 0) * 100, '%')}
          ${listRow('商品收益代理', Number(c.commodityReturn || 0) * 100, '%')}
          ${listRow('增长', Number(c.growth || 0) * 100, '%')}
          ${listRow('通胀', Number(c.inflation || 0) * 100, '%')}
          ${listRow('事件风险', Number(c.eventRisk || 0) * 100, '%')}
          ${listRow('杠杆压力', Number(c.leverageStress || 0) * 100, '%')}
        </div></div>
        <div class="card"><div class="card-head"><div><div class="card-title">行业 Tilt</div><div class="card-sub">世界/商品/股票/资金流合成</div></div></div><div class="table-wrap"><table><thead><tr><th>行业</th><th>Tilt</th></tr></thead><tbody>
          ${Object.entries(sectors).map(([k, v]) => `<tr><td>${esc(k)}</td><td class="${sign(Number(v) * 100)}">${signed(Number(v) * 100)}%</td></tr>`).join('') || emptyRow(2, '暂无传导数据')}
        </tbody></table></div></div>
      </div>
      <div class="grid g2" style="margin-top:14px">
        <div class="card"><div class="card-head"><div><div class="card-title">货币与信用</div><div class="card-sub"> Monetary / Credit</div></div></div><div class="card-pad list">
          ${Object.entries(m).slice(0, 22).map(([k, v]) => `<div class="list-row"><span>${esc(k)}</span><b class="mono">${typeof v === 'number' ? fmtNum(v) : esc(v)}</b></div>`).join('') || '<div class="empty">暂无数据</div>'}
        </div></div>
        <div class="card"><div class="card-head"><div><div class="card-title">全球宏观</div><div class="card-sub">World Macro</div></div></div><div class="card-pad list">
          ${Object.entries(w).slice(0, 22).map(([k, v]) => `<div class="list-row"><span>${esc(k)}</span><b class="mono">${typeof v === 'number' ? fmtNum(v) : esc(v)}</b></div>`).join('') || '<div class="empty">暂无数据</div>'}
        </div></div>
      </div>`;
  }

  function renderCommodities() {
    const q = state.search.trim().toLowerCase();
    const list = state.commodities.filter(c => !q ||
      `${c.name || ''} ${c.itemId || ''} ${c.industry || ''}`.toLowerCase().includes(q));
    const up = list.filter(c => Number(c.dayChange) > 0).length;
    const down = list.filter(c => Number(c.dayChange) < 0).length;
    const vol = list.length ? list.reduce((a, c) => a + Number(c.volatility || 0), 0) / list.length : 0;
    app.innerHTML = pageHead('商品市场', `${list.length} 个物品/流体 · 配方价值 + 供需 + 现实价格动态`,
      `<span class="chip">上涨 ${up} / 下跌 ${down}</span><span class="chip">平均波动 ${fmtNum(vol * 100)}%</span>`) +
      `<div class="grid g4">
        ${kpi('商品数量', fmtInt(list.length))}
        ${kpi('上涨', fmtInt(up), `<span class="chip">下跌 ${down}</span>`)}
        ${kpi('平均日波动', fmtNum(vol * 100) + '%')}
        ${kpi('价格机制', 'OU + 动量', `<span class="chip">成本锚 / 供需 / 季节性</span>`)}
      </div>
      <div class="card" style="margin-top:14px"><div class="card-head"><div><div class="card-title">商品报价</div><div class="card-sub">真实价格 vs 公允价值 · 波动率 · 库存压力</div></div></div><div class="table-wrap"><table><thead><tr><th>商品</th><th>价格</th><th>日涨跌</th><th>相对基准</th><th>公允价值</th><th>成本锚</th><th>动量</th><th>波动率</th><th>季节性</th><th>供给 / 需求</th><th>走势</th></tr></thead><tbody>
        ${list.slice(0, 200).map(c => `<tr><td><div class="sym"><b>${esc(c.name || c.itemId)}</b><span>${esc(c.itemId || '')}</span></div></td><td>${fmtNum(c.price)}</td><td class="${sign(c.dayChange)}">${pctChange(c.dayChange)}</td><td class="${sign(c.change)}">${pctChange(c.change)}</td><td>${fmtNum(c.fairValue)}</td><td>${fmtNum(c.costAnchor)}</td><td class="${sign(Number(c.momentum) * 100)}">${signed(Number(c.momentum || 0) * 100)}%</td><td>${fmtNum(Number(c.volatility || 0) * 100)}%</td><td class="${sign(Number(c.seasonality) * 100)}">${signed(Number(c.seasonality || 0) * 100)}%</td><td>${fmtInt(c.supply)} / ${fmtInt(c.demand)}</td><td>${sparkline(c.sparkline, Number(c.dayChange) >= 0)}</td></tr>`).join('') || emptyRow(11, '暂无商品数据')}
      </tbody></table></div></div>`;
  }

  function renderBank() {
    const c = state.bankCard || {};
    const banks = state.banks || [];
    if (!c.hasCard) {
      app.innerHTML = pageHead('银行卡与支付', '开卡 → 设置支付密码 → 转账 / 商户付款',
        `<span class="chip">未开卡</span>`) +
        `<div class="grid g2">
          <div class="card card-pad"><div class="card-title">办理储蓄卡</div>
            <div class="card-sub" style="margin-bottom:12px">每玩家一张；开卡后设置 4-6 位支付密码即可交易。</div>
            <select id="bankSelect">${banks.map(b => `<option value="${esc(b.bankId)}">${esc(b.name || b.bankId)}</option>`).join('') || '<option value="BANK_A">BANK_A</option>'}</select>
            <div style="margin-top:12px"><button class="btn primary" id="bankIssue">开卡</button></div>
          </div>
          <div class="card card-pad"><div class="card-title">为什么需要银行卡</div>
            <div class="card-sub">股票、CZCE 期货、商品市场、公司 IPO 都要求持卡。支付走银行活期账户，不直接动背包。</div>
            <div class="list" style="margin-top:12px">
              <div class="list-row"><span>现金</span><b>${fmtMoney(c.cash)}</b></div>
              <div class="list-row"><span>支付通道</span><b>活期账户 → 对方活期</b></div>
            </div>
          </div>
        </div>`;
    } else {
      const st = state.bankStatement || [];
      app.innerHTML = pageHead('银行卡与支付', '转账 · 商户付款 · 密码 · 冻结 · 流水',
        `<span class="chip">${c.frozen ? '已冻结' : '正常'}</span><span class="chip">${esc(c.bankId)}</span>`) +
        `<div class="grid g2">
          <div class="card card-pad">
            <div class="card-sub">CARD / ${esc(c.bankId)}</div>
            <div style="font-size:22px;letter-spacing:2px;margin:14px 0">${esc(c.cardMasked)}</div>
            <div class="grid g2" style="margin-top:10px">
              ${kpi('活期余额', fmtMoney(c.depositBalance))}
              ${kpi('今日已用', fmtMoney(c.spentToday), `<span class="chip">限额 ${fmtMoney(c.dailyLimit)}</span>`)}
            </div>
            <div class="list" style="margin-top:12px">
              <div class="list-row"><span>支付密码</span><b>${c.pinSet ? '已设置' : '未设置'}</b></div>
              <div class="list-row"><span>钱包现金</span><b>${fmtMoney(c.cash)}</b></div>
            </div>
            <div style="margin-top:12px;display:flex;gap:8px;flex-wrap:wrap">
              <input id="bankMove" type="number" min="0.01" step="0.01" placeholder="存取金额" style="width:130px">
              <button class="btn" id="bankDeposit">存款</button>
              <button class="btn" id="bankWithdraw">取款</button>
            </div>
            <div style="margin-top:8px;display:flex;gap:8px;flex-wrap:wrap">
              <button class="btn" id="bankFreeze">${c.frozen ? '解冻' : '冻结'}</button>
              <button class="btn" id="bankPinBtn">设置/修改密码</button>
            </div>
          </div>
          <div class="card card-pad">
            <div class="card-title">支付 / 转账</div>
            <div class="card-sub">目标为对方 16 位卡号；转账费率 0.1%（最低 0.01，最高 50）。</div>
            <div style="display:grid;gap:8px;margin-top:12px">
              <input id="bankTarget" placeholder="对方卡号">
              <input id="bankAmount" type="number" min="0.01" step="0.01" placeholder="金额">
              <input id="bankPayPin" placeholder="支付密码（4-6 位）" maxlength="6">
              <input id="bankMemo" placeholder="备注（可选）">
              <div style="display:flex;gap:8px">
                <button class="btn primary" id="bankTransfer">转账</button>
                <button class="btn" id="bankPay">商户付款</button>
              </div>
            </div>
          </div>
        </div>
        <div class="card" style="margin-top:14px"><div class="card-head"><div><div class="card-title">交易流水</div><div class="card-sub">最近 50 笔</div></div></div><div class="table-wrap"><table><thead><tr><th>#</th><th>方向</th><th>金额</th><th>手续费</th><th>渠道</th><th>对方</th><th>备注</th><th>状态</th></tr></thead><tbody>
          ${st.map(t => `<tr><td>${t.id}</td><td class="${t.direction === 'IN' ? 'up' : 'down'}">${t.direction === 'IN' ? '收入' : '支出'}</td><td>${fmtMoney(t.amount)}</td><td>${fmtMoney(t.fee)}</td><td>${esc(t.channel)}</td><td>${esc(t.counterparty || '')}</td><td>${esc(t.memo || '')}</td><td>${esc(t.status)}</td></tr>`).join('') || emptyRow(8, '暂无流水')}
        </tbody></table></div></div>`;
    }
    const issue = $('#bankIssue');
    if (issue) issue.addEventListener('click', async () => {
      const bankId = $('#bankSelect')?.value || 'BANK_A';
      try {
        const r = await api('/api/bank/card', { method: 'POST', body: JSON.stringify({ bankId }) });
        toast(r.message || '开卡请求已发送', !r.success);
        if (r.success) { await loadCore(); render(); }
      } catch (e) { toast(e.message, true); }
    });
    const pinBtn = $('#bankPinBtn');
    if (pinBtn) pinBtn.addEventListener('click', async () => {
      const pin = prompt('设置 4-6 位支付密码');
      if (!pin) return;
      try {
        const r = await api('/api/bank/card/pin', { method: 'POST', body: JSON.stringify({ pin }) });
        toast(r.message || '密码请求已发送', !r.success);
        if (r.success) { await loadCore(); render(); }
      } catch (e) { toast(e.message, true); }
    });
    const freeze = $('#bankFreeze');
    if (freeze) freeze.addEventListener('click', async () => {
      const path = c.frozen ? '/api/bank/card/unfreeze' : '/api/bank/card/freeze';
      try {
        const r = await api(path, { method: 'POST', body: '{}' });
        toast(r.message || '操作完成', !r.success);
        if (r.success) { await loadCore(); render(); }
      } catch (e) { toast(e.message, true); }
    });
    const move = async (deposit) => {
      const amount = Number($('#bankMove')?.value);
      if (!(amount > 0)) return toast('存取金额必须为正', true);
      try {
        const r = await api(deposit ? '/api/bank/deposit' : '/api/bank/withdraw', {
          method: 'POST', body: JSON.stringify({ bankId: c.bankId, amount })
        });
        toast(r.message || (deposit ? '存款完成' : '取款完成'), !r.success);
        if (r.success) { await loadCore(); render(); }
      } catch (e) { toast(e.message, true); }
    };
    if ($('#bankDeposit')) $('#bankDeposit').addEventListener('click', () => move(true));
    if ($('#bankWithdraw')) $('#bankWithdraw').addEventListener('click', () => move(false));
    const pay = async (merchant) => {
      const toCard = $('#bankTarget')?.value.trim();
      const amount = Number($('#bankAmount')?.value);
      const pin = $('#bankPayPin')?.value.trim();
      const memo = $('#bankMemo')?.value.trim() || '';
      if (!toCard || !(amount > 0) || !pin) return toast('卡号 / 金额 / 密码均必填', true);
      try {
        const r = await api(merchant ? '/api/bank/pay' : '/api/bank/transfer', {
          method: 'POST', body: JSON.stringify({ toCard, amount, pin, memo })
        });
        toast(r.message || (merchant ? '支付完成' : '转账完成'), !r.success);
        if (r.success) { await loadCore(); render(); }
      } catch (e) { toast(e.message, true); }
    };
    if ($('#bankTransfer')) $('#bankTransfer').addEventListener('click', () => pay(false));
    if ($('#bankPay')) $('#bankPay').addEventListener('click', () => pay(true));
  }

  function renderCompanies() {
    const q = state.search.trim().toLowerCase();
    const list = state.companies.filter(c => !q || JSON.stringify(c).toLowerCase().includes(q));
    app.innerHTML = pageHead('公司基本面', `${state.companies.length} 家公司 · EPS / ROE / 估值`,
      `<span class="chip">AI 公司</span>`) +
      `<div class="card"><div class="table-wrap"><table><thead><tr><th>公司</th><th>行业</th><th>股票</th><th>EPS</th><th>ROE</th><th>净资产</th><th>机器/多方块</th><th>资产估值</th><th>产能利用</th><th>日产出</th><th>AK增长</th><th>AK回报</th><th>营收增长</th><th>股息率</th></tr></thead><tbody>
        ${list.slice(0, 100).map(c => `<tr data-company="${esc(c.name || c.stockId || '')}"><td><div class="sym"><b>${esc(c.name || c.stockId)}</b><span>${esc(c.stockId || '')}</span></div></td><td>${esc(c.industry || '—')}</td><td>${esc(c.stockId || '—')}</td><td>${fmtNum(c.eps)}</td><td>${fmtNum(c.roe)}</td><td>${fmtMoney(c.nav ?? c.netAssetValue)}</td><td>${fmtInt(c.machineCount || 0)} / ${fmtInt(c.multiblockCount || 0)}</td><td>${fmtMoney(c.appraisedAssetValue ?? 0)}</td><td>${fmtNum(Number(c.productionUtilization || 0) * 100)}%</td><td>${fmtMoney(c.productionOutputValue ?? 0)}</td><td class="${sign(Number(c.akGrowth || 0) * 100)}">${signed(Number(c.akGrowth || 0) * 100)}%</td><td class="${sign(Number(c.akReturn || 0) * 100)}">${signed(Number(c.akReturn || 0) * 100)}%</td><td>${fmtNum(c.revenueGrowth)}%</td><td>${fmtNum(c.dividendYield)}%</td></tr>`).join('') || emptyRow(14, '暂无公司')}
      </tbody></table></div></div>`;
  }

  function bindGlobal() {
    $('#refreshBtn').onclick = async () => {
      $('#refreshBtn').textContent = '…';
      await loadCore();
      if (['portfolio', 'orders'].includes(state.route)) await (state.route === 'portfolio' ? loadPositions() : loadOrders());
      render();
      $('#refreshBtn').textContent = '↻';
      toast('已刷新');
    };
    $('#avatar').onclick = () => {
      modal('绑定交易玩家', `
        <div class="field"><label>在线玩家名 / UUID</label><input id="sessionPlayer" placeholder="输入在线玩家名" value="${esc(state.account.playerName && state.account.playerName !== '—' ? state.account.playerName : '')}"></div>
        <div class="page-sub" style="margin:10px 0 14px">HTTP 下单会绑定到该玩家；不绑定时使用第一个在线玩家。</div>
        <button class="btn primary" id="sessionSave">绑定并刷新</button>`);
      $('#sessionSave').onclick = async () => {
        const name = ($('#sessionPlayer').value || '').trim();
        if (!name) return toast('请输入玩家名', true);
        try {
          const res = await api('/api/session', { method: 'POST', body: JSON.stringify({ playerName: name }) });
          if (!res || !res.success) return toast((res && res.message) || '绑定失败', true);
          closeModal();
          await loadCore();
          render();
          toast('已绑定 ' + (res.playerName || name));
        } catch (e) { toast(e.message, true); }
      };
    };
    $('#menuBtn').onclick = () => $('#sidebar').classList.toggle('open');
    $('#modalClose').onclick = closeModal;
    $('#modal').onclick = e => { if (e.target.id === 'modal') closeModal(); };
    $('#globalSearch').addEventListener('input', e => {
      state.search = e.target.value;
      if (state.route === 'stocks') renderStocks();
      else if (state.route === 'futures') renderFutures();
      else if (state.route === 'companies') renderCompanies();
    });
    $('#globalSearch').addEventListener('keydown', e => {
      if (e.key !== 'Enter') return;
      const q = state.search.trim().toLowerCase();
      const stock = state.stocks.find(s => String(s.stockId).toLowerCase().includes(q) || String(s.code || '').toLowerCase().includes(q));
      const fut = state.futures.find(f => f.code.toLowerCase().includes(q));
      if (stock) { state.selectedStock = stock.stockId; setRoute('stocks'); }
      else if (fut) { state.selectedFuture = fut.code; setRoute('futures'); }
    });
    window.addEventListener('hashchange', () => {
      const r = routeFromHash();
      if (r !== state.route) setRoute(r, { silent: true });
    });
    window.addEventListener('resize', () => { if (state.chart) state.chart.resize(); });
    $$('#nav .nav-item, #mobileNav a').forEach(el => el.addEventListener('click', e => {
      // hashchange handles; close mobile menu immediately
      $('#sidebar').classList.remove('open');
    }));
  }

  async function boot() {
    try {
      await loadCore();
      if (state.route === 'portfolio' || state.route === 'orders') await loadPositions();
      if (state.route === 'orders') await loadOrders();
      if (state.route === 'options') await loadOptions(state.optionCode);
      state.loading = false;
      setRoute(state.route, { silent: true });
    } catch (e) {
      state.loading = false;
      app.innerHTML = `<div class="card card-pad"><div class="empty">无法连接交易服务：${esc(e.message)}<br><br><button class="btn primary" onclick="location.reload()">重试</button></div></div>`;
      setOnline(false);
    }
  }

  setInterval(() => {
    if (!state.online) return;
    const active = document.activeElement;
    if (active && ['INPUT', 'SELECT', 'TEXTAREA'].includes(active.tagName)) return;
    if (!['dashboard', 'stocks', 'commodities', 'bank', 'futures', 'portfolio'].includes(state.route)) return;
    Promise.allSettled([loadCore()]).then(() => {
      if (state.route === 'portfolio') loadPositions().then(render);
      else render();
    });
  }, 9000);

  state.route = routeFromHash();
  bindGlobal();
  boot();
})();
