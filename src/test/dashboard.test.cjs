const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, '../main/resources/index.html'), 'utf8');
const script = [...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)]
    .map(match => match[1]).join('\n');
const now = Date.parse('2026-09-21T12:00:00Z');

function harness(options = {}) {
    const elements = [];
    const byId = new Map();
    function element(attributes = {}) {
        const item = {
            id: attributes.id || '',
            className: attributes.class || '',
            dataset: { series: attributes['data-series'] },
            value: attributes.value || '',
            textContent: '',
            innerHTML: '',
            hidden: false,
            disabled: false,
            style: {},
            children: [],
            attributes: { ...attributes },
            getContext: () => ({}),
            setAttribute(name, value) { this.attributes[name] = value; },
            appendChild(child) { this.children.push(child); },
            replaceChildren(...children) { this.children = children; },
            focus() { document.activeElement = this; }
        };
        item.classList = {
            toggle(name, enabled) {
                const names = new Set(item.className.split(/\s+/).filter(Boolean));
                if (enabled) names.add(name);
                else names.delete(name);
                item.className = [...names].join(' ');
            }
        };
        return item;
    }
    for (const match of html.matchAll(/<[a-z][\w-]*\b([^>]*)>/gi)) {
        const attributes = Object.fromEntries([...match[1].matchAll(/([\w-]+)="([^"]*)"/g)]
            .map(attribute => [attribute[1], attribute[2]]));
        const item = element(attributes);
        elements.push(item);
        if (item.id) byId.set(item.id, item);
    }
    const document = {
        activeElement: null,
        getElementById(id) {
            assert.ok(byId.has(id), 'Missing dashboard element: ' + id);
            return byId.get(id);
        },
        createElement: () => element(),
        querySelectorAll(selector) {
            if (selector === '[data-series]') return elements.filter(item => item.dataset.series);
            if (selector.startsWith('.')) return elements.filter(item => item.className.split(/\s+/).includes(selector.slice(1)));
            throw new Error('Unexpected selector: ' + selector);
        }
    };
    let chartCount = 0;
    class MockChart {
        constructor(context, configuration) {
            this.data = configuration.data;
            this.options = configuration.options;
            this.updates = [];
            chartCount++;
        }
        update(mode) { this.updates.push(mode); }
        destroy() { throw new Error('Polling must not destroy the chart'); }
    }
    let clock = now;
    class ClockDate extends Date {
        constructor(...args) { super(...(args.length ? args : [clock])); }
        static now() { return clock; }
    }
    const context = vm.createContext({
        document,
        window: { innerWidth: 390 },
        Chart: MockChart,
        AbortController,
        Date: ClockDate,
        fetch: () => new Promise(() => {}),
        setInterval: () => 0,
        setTimeout: () => 0,
        console
    });
    if (options.localStorage) context.localStorage = options.localStorage;
    if (options.fetch) context.fetch = options.fetch;
    new vm.Script(script, { filename: 'dashboard-inline.js' }).runInContext(context);
    return {
        context,
        get: id => document.getElementById(id),
        run: source => vm.runInContext(source, context),
        advance: milliseconds => { clock += milliseconds; },
        chartCount: () => chartCount
    };
}

const row = (minutesAgo, overrides = {}) => ({
    timestamp: new Date(now - minutesAgo * 60000).toISOString(),
    gap_before: false,
    humidity: 52,
    fan_speed: 2,
    commanded_speed: 3,
    target_speed: 3,
    policy_speed: 4,
    temp_supply: 20,
    temp_extract: 22,
    temp_outside: 12,
    temp_exhaust: 14,
    ...overrides
});
const response = data => ({ ok: true, json: async () => data });
const summary = (overrides = {}) => ({
    start: new Date(now - 86400000).toISOString(), end: new Date(now).toISOString(),
    stage_seconds: [0, 3600, 7200, 1800, 0], unknown_seconds: 73800,
    automatic_upshifts: 3, coverage_seconds: 12600, ...overrides
});
const setRows = (dashboard, rows) => dashboard.run(`updateChart(${JSON.stringify(rows)})`);

test('a recorded telemetry gap breaks chart lines instead of connecting observations', () => {
    const dashboard = harness();
    setRows(dashboard, [row(120), row(60, { gap_before: true }), row(0)]);
    for (const series of ['humidity', 'fanSpeed', 'tempSupply', 'commandedSpeed']) {
        assert.equal(dashboard.run(`chart.data.datasets.find(item => item.seriesKey === '${series}').data.includes(null)`), true);
    }
});

test('gap sentinels break every dataset and preserve event tooltip indexes and boundary rows', () => {
    const dashboard = harness();
    dashboard.run('currentRange = "month"');
    setRows(dashboard, [row(120), row(60, { gap_before: true, control_event: true, control_reason: 'After gap' }), row(0)]);
    assert.equal(dashboard.run('historyRows.length'), 4);
    assert.equal(dashboard.run('historyRows[0].timestamp'), now - 120 * 60000);
    assert.equal(dashboard.run('historyRows[2].timestamp'), now - 60 * 60000);
    assert.equal(dashboard.run('chart.data.datasets.every(dataset => dataset.data[1] === null && dataset.spanGaps === false)'), true);
    assert.equal(dashboard.get('recent-events').children.length, 1);
    assert.match(dashboard.run('chart.options.plugins.tooltip.callbacks.label({dataset: {seriesKey: "controlEvents"}, dataIndex: 2}).join(" ")'), /After gap/);
    assert.match(dashboard.get('gap-count').textContent, /1/);
});

test('older raw history infers gaps using freshness, never bucketed spacing or explicit false', () => {
    const dashboard = harness();
    const olderRow = minutes => {
        const point = row(minutes);
        delete point.gap_before;
        return point;
    };
    setRows(dashboard, [olderRow(2), olderRow(0)]);
    assert.equal(dashboard.run('historyRows.length'), 3);
    dashboard.run('lastLiveData = {stale_after_seconds: 150}');
    setRows(dashboard, [olderRow(2), olderRow(0)]);
    assert.equal(dashboard.run('historyRows.length'), 2);
    setRows(dashboard, [row(120), row(0)]);
    assert.equal(dashboard.run('historyRows.length'), 2);
    for (const range of ['week', 'month']) {
        dashboard.run(`currentRange = "${range}"`);
        setRows(dashboard, [olderRow(120), olderRow(0)]);
        assert.equal(dashboard.run('historyRows.length'), 2);
    }
});

test('missing measurements remain null without legacy substitution or bridging', () => {
    const dashboard = harness();
    setRows(dashboard, [row(2), row(1, { humidity: null, temp_supply: null, temp: 99, fan_speed: null, bypass_open: null }), row(0)]);
    for (const series of ['humidity', 'tempSupply', 'fanSpeed', 'bypass']) {
        assert.equal(dashboard.run(`chart.data.datasets.find(dataset => dataset.seriesKey === '${series}').data[1]`), null);
        assert.equal(dashboard.run(`chart.data.datasets.find(dataset => dataset.seriesKey === '${series}').spanGaps`), false);
    }
});

test('inline scripts compile and operating data stays measured, signed and text-only', () => {
    const dashboard = harness();
    const reason = '<img src=x onerror=alert(1)> Gentle recovery';
    dashboard.run(`updateLiveDisplay(${JSON.stringify({
        humidity: 50, fan_speed: 1, target_speed: 2, commanded_speed: 3,
        policy_speed: 2, boost: true, control_reason: reason,
        humidity_delta: -0.4, moisture: 8.23, moisture_change_30m: -0.75,
        boost_recovery_target: 45.6, moisture_baseline: 7.1
    })})`);
    assert.equal(dashboard.get('operating-reason').textContent, reason);
    assert.equal(dashboard.get('operating-reason').innerHTML, '');
    assert.equal(dashboard.get('operating-stages').textContent, 'Actual stage 1 · Target 2 · Commanded 3 · Policy request 2');
    assert.match(dashboard.get('operating-detail').textContent, /Gentle drying.*-0.4 pp/);
    assert.equal(dashboard.get('live-moisture').textContent, '8.23 g/kg');
    assert.equal(dashboard.get('moisture-change').textContent, '-0.75 g/kg');
    assert.match(dashboard.get('recovery-baseline').textContent, /45.6% RH.*7.10 g\/kg/);
    assert.doesNotMatch(html, /fa-shower|completion percentage/i);
    dashboard.run('updateLiveDisplay({boost: true, policy_speed: 4, humidity_delta: 9, control_reason: "Shower boost", moisture_change_30m: 0.5})');
    assert.match(dashboard.get('operating-detail').textContent, /Strong shower boost/);
    assert.equal(dashboard.get('moisture-change').textContent, '+0.50 g/kg');
    dashboard.run('updateLiveDisplay({moisture: null, moisture_change_30m: null, humidity_delta: null})');
    assert.equal(dashboard.get('live-moisture').textContent, 'Unavailable');
    assert.equal(dashboard.get('moisture-change').textContent, 'Unavailable');
    assert.match(dashboard.get('operating-detail').textContent, /Unavailable/);
    assert.doesNotMatch(dashboard.get('operating-stages').textContent, /undefined|null/);
});

test('freshness uses sampled_at, with unknown, stale and unreachable states', () => {
    const dashboard = harness();
    dashboard.run('liveReachable = true; lastLiveData = {}; updateFreshness()');
    assert.equal(dashboard.get('connection-label').textContent, 'Device freshness unknown');
    dashboard.run(`lastLiveData = {sampled_at: ${JSON.stringify(new Date(now - 76000).toISOString())}}; updateFreshness()`);
    assert.equal(dashboard.get('connection-label').textContent, 'API connected · Device stale');
    dashboard.run('lastLiveData.stale_after_seconds = 90; updateFreshness()');
    assert.equal(dashboard.get('connection-label').textContent, 'Connected · Device fresh');
    dashboard.run('lastLiveData.device_poll_failed = true; updateFreshness()');
    assert.equal(dashboard.get('connection-label').textContent, 'API connected · Device poll failed');
    dashboard.run('lastLiveData.device_poll_failed = false');
    dashboard.run('lastLiveData.sampled_at = null; updateFreshness()');
    assert.equal(dashboard.get('connection-label').textContent, 'Device freshness unknown');
    dashboard.run('liveReachable = false; updateFreshness()');
    assert.equal(dashboard.get('connection-label').textContent, 'API unreachable');
    assert.equal(dashboard.get('last-updated').textContent, 'Device sample time unavailable');
});

test('startup sentinels and paused recovery are never presented as active measurements', () => {
    const dashboard = harness();
    dashboard.run('updateLiveDisplay({sampled_at: null, humidity: -1, temp_supply: -1, rpm: -1, fan_speed: -1})');
    assert.equal(dashboard.get('live-humidity').textContent, 'Unavailable');
    assert.equal(dashboard.get('live-temp').textContent, '--°C');
    assert.equal(dashboard.get('live-speed').textContent, 'Unavailable');
    dashboard.run('updateLiveDisplay({boost: true, humidity_delta: 6, policy_speed: 3, static_mode: true})');
    assert.doesNotMatch(dashboard.get('operating-detail').textContent, /drying|boost/i);
});

test('history failure never relabels a reachable live API as offline', async () => {
    const dashboard = harness();
    dashboard.context.fetch = async url => url === 'api/live'
        ? response({ sampled_at: new Date(now).toISOString(), humidity: 51 })
        : { ok: false };
    await dashboard.run('fetchData()');
    assert.equal(dashboard.get('connection-label').textContent, 'Connected · Device fresh');
    assert.match(dashboard.get('history-status').textContent, /History unavailable/);
    dashboard.context.fetch = async url => {
        if (url === 'api/live') throw new Error('Network unavailable');
        return response([row(0)]);
    };
    await dashboard.run('fetchData()');
    assert.equal(dashboard.get('connection-label').textContent, 'API unreachable');
    assert.equal(dashboard.chartCount(), 1);
});

test('default humidity and stage axes, separate temperatures, preserved custom toggles', () => {
    const dashboard = harness();
    setRows(dashboard, [row(60), row(0)]);
    const visible = () => dashboard.run('chart.data.datasets.filter(dataset => !dataset.hidden).map(dataset => dataset.seriesKey).join(",")');
    assert.equal(visible(), 'humidity,fanSpeed,controlEvents');
    assert.equal(dashboard.run('chart.options.scales.y.display'), true);
    assert.equal(dashboard.run('chart.options.scales.y2.display'), true);
    assert.equal(dashboard.run('chart.options.scales.yTemp.display'), false);
    dashboard.run('setChartView("temperatures")');
    assert.equal(visible(), 'tempSupply,tempExtract,tempOutside,controlEvents');
    assert.equal(dashboard.run('chart.options.scales.y.display'), false);
    assert.equal(dashboard.run('chart.options.scales.yTemp.display'), true);
    dashboard.run('setChartView("custom"); toggleSeries("commandedSpeed"); toggleSeries("bypass"); toggleSeries("tempExhaust")');
    setRows(dashboard, [row(60), row(0)]);
    assert.match(visible(), /commandedSpeed,bypass/);
    assert.match(visible(), /tempExhaust/);
    dashboard.run('setChartView("climate"); setChartView("custom")');
    assert.match(visible(), /commandedSpeed,bypass/);
    assert.equal(dashboard.chartCount(), 1);
});

test('date inputs and slider zoom retain the same chart and bounds during polling', () => {
    const dashboard = harness();
    setRows(dashboard, [row(120), row(60), row(0)]);
    dashboard.run('resizeWindow(50)');
    const start = dashboard.run('chart.options.scales.x.min');
    const end = dashboard.run('chart.options.scales.x.max');
    assert.equal(end - start, 60 * 60000);
    setRows(dashboard, [row(120), row(60), row(-5)]);
    assert.equal(dashboard.chartCount(), 1);
    assert.equal(dashboard.run('chart.options.scales.x.min'), start);
    assert.equal(dashboard.run('chart.options.scales.x.max'), end);
    dashboard.get('window-start').value = dashboard.run(`localDateInput(${now - 30 * 60000})`);
    dashboard.get('window-end').value = dashboard.run(`localDateInput(${now})`);
    dashboard.run('applyVisibleWindow()');
    assert.equal(dashboard.run('chart.options.scales.x.max - chart.options.scales.x.min'), 30 * 60000);
    dashboard.get('window-start').value = dashboard.get('window-end').value;
    dashboard.run('applyVisibleWindow()');
    assert.match(dashboard.get('window-error').textContent, /start before the end/);
    dashboard.run('resetZoom()');
    assert.equal(dashboard.run('visibleWindow'), null);
    assert.equal(dashboard.get('window-percent').textContent, '100%');
    assert.equal(dashboard.run('chart.options.scales.x.max'), now + 5 * 60000);
});

test('events use only explicit flags and recorded reasons, never inferred speed targets', () => {
    const dashboard = harness();
    setRows(dashboard, [
        row(60, { control_event: false, control_reason: 'Not an event' }),
        row(40, { control_event: true, control_reason: null }),
        row(20, { control_event: true, control_reason: 'Heat-loss guard <b>active</b>', fan_speed: 1, commanded_speed: 3, target_speed: 2 }),
        row(0, { fan_speed: 4, commanded_speed: 4 })
    ]);
    assert.equal(dashboard.get('recent-events').children.length, 2);
    const recent = dashboard.get('recent-events').children[0].children[0];
    assert.match(recent.textContent, /Heat-loss guard <b>active<\/b>.*Commanded 3.*Actual 1.*Target 2/);
    assert.equal(recent.innerHTML, '');
    assert.match(dashboard.get('recent-events').children[1].children[0].textContent, /Reason unavailable/);
    assert.equal(dashboard.run('chart.data.datasets.find(dataset => dataset.seriesKey === "controlEvents").data.filter(value => value !== null).length'), 2);
    dashboard.run('resizeWindow(10)');
    assert.equal(dashboard.get('recent-events').children.length, 0);
    assert.equal(dashboard.get('event-empty').hidden, false);
});

test('range fetch races are aborted and late responses cannot replace the selected range', async () => {
    const dashboard = harness();
    const pending = [];
    dashboard.context.fetch = (url, options) => new Promise(resolve => pending.push({ url, signal: options.signal, resolve }));
    const first = dashboard.run('setRange("3h")');
    const second = dashboard.run('setRange("week")');
    assert.equal(pending[0].signal.aborted, true);
    assert.equal(pending[1].url, 'api/history?range=week');
    pending[1].resolve(response([row(60), row(0, { humidity: 60 })]));
    await second;
    pending[0].resolve(response([row(0, { humidity: 99 })]));
    await first;
    assert.equal(dashboard.run('historyRows.at(-1).humidity'), 60);
    assert.equal(dashboard.run('currentRange'), 'week');
    assert.equal(dashboard.get('btn-week').attributes['aria-pressed'], 'true');
    for (const range of ['3h', '6h', '12h', 'day', 'week', 'month']) {
        const request = dashboard.run(`setRange(${JSON.stringify(range)})`);
        assert.equal(pending.at(-1).url, 'api/history?range=' + range);
        pending.at(-1).resolve(response([]));
        await request;
        assert.equal(dashboard.get('history-status').textContent, 'No history in this range');
    }
});

test('late live responses cannot overwrite newer telemetry', async () => {
    const dashboard = harness();
    const pending = [];
    dashboard.context.fetch = () => new Promise(resolve => pending.push(resolve));
    const older = dashboard.run('fetchLive()');
    const newer = dashboard.run('fetchLive()');
    pending[1](response({ sampled_at: new Date(now).toISOString(), humidity: 60 }));
    await newer;
    pending[0](response({ sampled_at: null, humidity: 10 }));
    await older;
    assert.equal(dashboard.get('live-humidity').textContent, '60%');
    assert.equal(dashboard.get('connection-label').textContent, 'Connected · Device fresh');
});

test('unavailable history retains the loaded chart with an explicit warning', async () => {
    const dashboard = harness();
    setRows(dashboard, [row(60), row(0)]);
    dashboard.run('resizeWindow(25)');
    const start = dashboard.run('chart.options.scales.x.min');
    dashboard.context.fetch = async () => { throw new Error('History unavailable'); };
    await dashboard.run('fetchHistory()');
    assert.equal(dashboard.get('history-status').textContent, 'History unavailable · Showing previously loaded data');
    assert.equal(dashboard.run('chart.options.scales.x.min'), start);
    assert.equal(dashboard.run('historyRows.length'), 2);
    await dashboard.run('setRange("month")');
    assert.equal(dashboard.get('history-status').textContent, 'History unavailable');
    assert.equal(dashboard.run('chart.data.labels.length'), 0);
    assert.equal(dashboard.run('visibleWindow'), null);
});

test('tab keyboard navigation and zoom controls retain accessible names', () => {
    const dashboard = harness();
    dashboard.run('navigateChartTabs({key: "ArrowRight", preventDefault() {}})');
    assert.equal(dashboard.run('chartView'), 'temperatures');
    assert.equal(dashboard.get('tab-temperatures').tabIndex, 0);
    assert.equal(dashboard.get('history-view').attributes['aria-labelledby'], 'tab-temperatures');
    assert.equal(dashboard.run('document.activeElement.id'), 'tab-temperatures');
    dashboard.run('navigateChartTabs({key: "End", preventDefault() {}})');
    assert.equal(dashboard.run('chartView'), 'custom');
    dashboard.run('navigateChartTabs({key: "Home", preventDefault() {}})');
    assert.equal(dashboard.run('chartView'), 'climate');
    assert.match(html, /title="Reset zoom" aria-label="Reset zoom"/);
    assert.match(html, /title="Apply time window" aria-label="Apply time window"/);
    assert.equal(dashboard.get('window-size').attributes.type, 'range');
    assert.equal(dashboard.get('window-start').attributes.type, 'datetime-local');
});

test('event buttons and actual canvas marker hits zoom to stable timestamp bounds', () => {
    const dashboard = harness();
    const rows = [row(120), row(60, { control_event: true, gap_before: true }), row(0)];
    setRows(dashboard, rows);
    dashboard.run('setChartView("temperatures")');
    const button = dashboard.get('recent-events').children[0].children[0];
    assert.equal(button.type, 'button');
    button.onclick();
    assert.equal(dashboard.run('visibleWindow.start'), now - 75 * 60000);
    assert.equal(dashboard.run('visibleWindow.end'), now - 45 * 60000);
    assert.equal(dashboard.run('document.activeElement.id'), 'window-label');
    setRows(dashboard, [...rows, row(-5)]);
    assert.equal(dashboard.run('visibleWindow.start'), now - 75 * 60000);
    assert.equal(dashboard.run('chartView'), 'temperatures');
    dashboard.run('resetZoom(); chart.getElementsAtEventForMode = () => [{datasetIndex: 0, index: 2}]; chart.options.onClick({}, [], chart)');
    assert.equal(dashboard.run('visibleWindow'), null);
    dashboard.run('chart.getElementsAtEventForMode = (event, mode, options) => options.intersect ? [{datasetIndex: 10, index: 2}] : []; chart.options.onClick({}, [], chart)');
    assert.equal(dashboard.run('visibleWindow.end'), now - 45 * 60000);
    dashboard.run(`zoomToEvent(${now})`);
    assert.equal(dashboard.run('visibleWindow.end'), now - 45 * 60000);
    setRows(dashboard, [row(120, {control_event: true}), row(0)]);
    dashboard.run(`zoomToEvent(${now - 120 * 60000})`);
    assert.equal(dashboard.run('visibleWindow.start'), now - 120 * 60000);
    assert.equal(dashboard.run('visibleWindow.end'), now - 105 * 60000);
});

test('chart preferences restore before fetching without restoring control state or zoom', () => {
    let saved = JSON.stringify({ range: 'week', view: 'custom', series: { rpm: true, humidity: false, fanSpeed: 'false', bogus: true }, visibleWindow: { start: 1, end: 2 }, mode: 'static' });
    const requests = [];
    const localStorage = { getItem: key => { assert.equal(key, 'genvex.chart.v1'); return saved; }, setItem: (key, value) => { saved = value; } };
    const dashboard = harness({ localStorage, fetch: (url, options) => { requests.push({ url, options }); return new Promise(() => {}); } });
    assert.equal(dashboard.run('currentRange'), 'week');
    assert.equal(dashboard.run('chartView'), 'custom');
    assert.equal(dashboard.run('chartSeriesVisibility.rpm'), true);
    assert.equal(dashboard.run('chartSeriesVisibility.fanSpeed'), true);
    assert.equal(dashboard.run('Object.hasOwn(chartSeriesVisibility, "bogus")'), false);
    assert.equal(dashboard.run('visibleWindow'), null);
    assert.equal(dashboard.get('btn-week').attributes['aria-pressed'], 'true');
    assert.equal(dashboard.get('tab-custom').attributes['aria-selected'], 'true');
    assert.ok(requests.some(request => request.url === 'api/history?range=week'));
    assert.ok(requests.every(request => !request.options?.method));
    dashboard.run('setChartView("temperatures"); toggleSeries("rpm"); setRange("3h")');
    assert.deepEqual(Object.keys(JSON.parse(saved)).sort(), ['range', 'series', 'view']);
    assert.equal(JSON.parse(saved).range, '3h');
    assert.equal(JSON.parse(saved).series.rpm, false);
    for (const value of ['{', 'null', '[]', '{"range":"invalid","view":"bad","series":null}']) {
        saved = value;
        const fallback = harness({ localStorage });
        assert.equal(fallback.run('currentRange'), 'day');
        assert.equal(fallback.run('chartView'), 'climate');
    }
    const denied = harness({ localStorage: { getItem() { throw new Error('Denied'); }, setItem() { throw new Error('Denied'); } } });
    assert.doesNotThrow(() => denied.run('toggleSeries("rpm"); setChartView("custom")'));
});

test('manual countdown uses receipt time and awaits confirmation after local expiry', () => {
    const dashboard = harness();
    dashboard.run('updateLiveDisplay({manual_override_active: true, manual_override_secs_left: 61})');
    assert.equal(dashboard.get('stop-boost').hidden, false);
    assert.match(dashboard.get('manual-countdown').textContent, /1:01 remaining/);
    dashboard.advance(1000);
    dashboard.run('updateFreshness()');
    assert.match(dashboard.get('manual-countdown').textContent, /1:00 remaining/);
    dashboard.advance(61000);
    dashboard.run('updateFreshness()');
    assert.equal(dashboard.get('manual-countdown').textContent, 'Timer elapsed · Awaiting live confirmation');
    assert.equal(dashboard.get('stop-boost').hidden, false);
    dashboard.run('updateLiveDisplay({manual_override_active: false, boost: true, control_reason: "Humidity recovery"})');
    assert.equal(dashboard.get('stop-boost').hidden, true);
    assert.equal(dashboard.get('manual-countdown').textContent, 'No manual boost');
    dashboard.run('updateLiveDisplay({manual_override_active: true, manual_override_secs_left: null})');
    assert.match(dashboard.get('manual-countdown').textContent, /time unavailable/);
});

test('stop uses its exact endpoint once, locks control widgets only and refreshes live without lowering actual stage', async () => {
    const dashboard = harness();
    dashboard.run('updateLiveDisplay({manual_override_active: true, manual_override_secs_left: 600, fan_speed: 4})');
    let resolveStop;
    const requests = [];
    dashboard.context.fetch = (url, options) => {
        requests.push({ url, options });
        if (url === 'api/fan/udluftning/stop') return new Promise(resolve => { resolveStop = resolve; });
        return Promise.resolve(response({ manual_override_active: false, monitor_only: true, boost: true, fan_speed: 4 }));
    };
    const pending = dashboard.run('stopManualBoost()');
    await dashboard.run('stopManualBoost(); triggerUdluftning(); triggerQuickBoost(120); applySystemControlMode(); applyStaticSpeedUpdate()');
    assert.equal(requests.length, 1);
    assert.equal(requests[0].options.method, 'POST');
    assert.equal(dashboard.get('system-mode-select').disabled, true);
    assert.equal(dashboard.get('udluftning-level').disabled, true);
    assert.equal(dashboard.get('stop-boost').disabled, true);
    assert.equal(dashboard.get('btn-week').disabled, false);
    resolveStop(response({ ok: true, pending: true, control_mode: 'monitor' }));
    await pending;
    assert.match(dashboard.get('toast').textContent, /Reevaluation pending/);
    assert.equal(requests.at(-1).url, 'api/live');
    assert.equal(dashboard.get('live-speed').textContent, '4');
    assert.equal(dashboard.get('system-mode-select').value, 'monitor');
    assert.equal(dashboard.get('stop-boost').hidden, true);
    assert.equal(dashboard.get('system-mode-select').disabled, false);
    await dashboard.run('stopManualBoost()');
    assert.equal(requests.length, 2);
});

test('stop errors never announce success and preserve controls and manual telemetry', async () => {
    for (const failure of [{ ok: false, status: 409 }, { ok: false, status: 500 }, response({ ok: false }), new Error('Network offline')]) {
        const dashboard = harness();
        dashboard.run('updateLiveDisplay({manual_override_active: true, manual_override_secs_left: 60})');
        dashboard.context.fetch = async url => {
            if (url === 'api/live') return response({ manual_override_active: true, manual_override_secs_left: 59 });
            if (failure instanceof Error) throw failure;
            return failure;
        };
        await dashboard.run('stopManualBoost()');
        assert.equal(dashboard.get('toast').className, 'show error');
        assert.doesNotMatch(dashboard.get('toast').textContent, /accepted|stopped/);
        assert.equal(dashboard.get('stop-boost').hidden, false);
        assert.equal(dashboard.get('stop-boost').disabled, false);
    }
});

test('boost start preserves selected level and quick duration payloads', async () => {
    const dashboard = harness();
    const requests = [];
    dashboard.context.fetch = async (url, options) => {
        if (options?.method === 'POST') {
            requests.push(JSON.parse(options.body));
            return response({ level: 3, minutes: 120 });
        }
        return url.includes('history') ? response([]) : response({});
    };
    dashboard.get('udluftning-level').value = '4';
    await dashboard.run('triggerUdluftning()');
    await dashboard.run('triggerQuickBoost(120)');
    assert.deepEqual(requests, [{ level: 4, duration_minutes: 30 }, { level: 3, duration_minutes: 120 }]);
});

test('last 24 hour summary fetches independently and survives chart range, tab and zoom changes', async () => {
    const dashboard = harness();
    const requests = [];
    dashboard.context.fetch = async url => {
        requests.push(url);
        return response(url === 'api/summary' ? summary() : url === 'api/live' ? {} : [row(120), row(0)]);
    };
    await dashboard.run('fetchData()');
    assert.ok(requests.includes('api/summary'));
    assert.equal(dashboard.get('summary-stage-0').textContent, '0s');
    assert.equal(dashboard.get('summary-stage-2').textContent, '2h 0m');
    assert.equal(dashboard.get('summary-bar-2').value, 7200);
    assert.equal(dashboard.get('summary-bar-2').max, 86400);
    assert.equal(dashboard.get('summary-bar-2').attributes['aria-valuetext'], '2h 0m');
    assert.equal(dashboard.get('summary-upshifts').textContent, '3');
    assert.equal(dashboard.get('summary-coverage').textContent, 'Coverage 3h 30m · Unobserved 20h 30m');
    await dashboard.run('setRange("month")');
    dashboard.run('setChartView("temperatures"); resizeWindow(10)');
    const zoom = dashboard.run('JSON.stringify(visibleWindow)');
    await dashboard.run('fetchData()');
    assert.equal(dashboard.run('JSON.stringify(visibleWindow)'), zoom);
    assert.equal(dashboard.get('summary-stage-2').textContent, '2h 0m');
    assert.deepEqual(requests.filter(url => url.includes('summary')), ['api/summary', 'api/summary']);
});

test('summary failure and all unknown coverage never become measured zeros', async () => {
    const dashboard = harness();
    dashboard.context.fetch = async url => response(url === 'api/live' ? { humidity: 51 } : url === 'api/summary' ? summary() : [row(0)]);
    await dashboard.run('fetchData()');
    for (const invalid of [null, {}, summary({ stage_seconds: [] }), summary({ stage_seconds: [0, null, 0, 0, 0] }), summary({ unknown_seconds: -1 }), summary({ end: 'bad' })]) {
        dashboard.context.fetch = async () => response(invalid);
        await dashboard.run('fetchSummary()');
        assert.equal(dashboard.get('summary-status').textContent, 'Summary unavailable');
        assert.equal(dashboard.get('summary-stage-0').textContent, 'Unavailable');
        assert.equal(dashboard.get('summary-upshifts').textContent, 'Unavailable');
        assert.equal(dashboard.get('summary-bar-0').hidden, true);
        assert.equal(dashboard.get('live-humidity').textContent, '51%');
        assert.equal(dashboard.run('historyRows.length'), 1);
    }
    dashboard.context.fetch = async () => { throw new Error('Offline'); };
    await dashboard.run('fetchSummary()');
    assert.equal(dashboard.get('summary-status').textContent, 'Summary unavailable');
    dashboard.context.fetch = async () => response(summary({ coverage_seconds: 0, unknown_seconds: 86400, stage_seconds: [0, 0, 0, 0, 0], automatic_upshifts: 0 }));
    await dashboard.run('fetchSummary()');
    assert.equal(dashboard.get('summary-status').textContent, 'No observed telemetry');
    assert.equal(dashboard.get('summary-coverage').textContent, 'Coverage 0s · Unobserved 24h 0m');
    assert.equal(dashboard.get('summary-upshifts').textContent, 'Unavailable');
    assert.equal(dashboard.get('summary-stage-0').textContent, 'Unavailable');
    dashboard.context.fetch = async () => response(summary({ automatic_upshifts: 0 }));
    await dashboard.run('fetchSummary()');
    assert.equal(dashboard.get('summary-upshifts').textContent, '0');
});

test('summary response races discard old successes and failures', async () => {
    const dashboard = harness();
    const pending = [];
    dashboard.context.fetch = () => new Promise(resolve => pending.push(resolve));
    const older = dashboard.run('fetchSummary()');
    const newer = dashboard.run('fetchSummary()');
    pending[1](response(summary({ automatic_upshifts: 5 })));
    await newer;
    pending[0](response(summary({ automatic_upshifts: 99 })));
    await older;
    assert.equal(dashboard.get('summary-upshifts').textContent, '5');
    const staleFailure = dashboard.run('fetchSummary()');
    const latest = dashboard.run('fetchSummary()');
    pending[3](response(summary({ automatic_upshifts: 6 })));
    await latest;
    pending[2]({ ok: false });
    await staleFailure;
    assert.equal(dashboard.get('summary-upshifts').textContent, '6');
    assert.equal(dashboard.get('summary-status').textContent, '');
});