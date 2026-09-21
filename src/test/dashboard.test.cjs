const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, '../main/resources/index.html'), 'utf8');
const script = [...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)]
    .map(match => match[1]).join('\n');
const now = Date.parse('2026-09-21T12:00:00Z');

function harness() {
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
    class ClockDate extends Date {
        constructor(...args) { super(...(args.length ? args : [now])); }
        static now() { return now; }
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
    new vm.Script(script, { filename: 'dashboard-inline.js' }).runInContext(context);
    return {
        context,
        get: id => document.getElementById(id),
        run: source => vm.runInContext(source, context),
        chartCount: () => chartCount
    };
}

const row = (minutesAgo, overrides = {}) => ({
    timestamp: new Date(now - minutesAgo * 60000).toISOString(),
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
const setRows = (dashboard, rows) => dashboard.run(`updateChart(${JSON.stringify(rows)})`);

test('a recorded telemetry gap breaks chart lines instead of connecting observations', () => {
    const dashboard = harness();
    setRows(dashboard, [row(120), row(60, { gap_before: true }), row(0)]);
    for (const series of ['humidity', 'fanSpeed', 'tempSupply', 'commandedSpeed']) {
        assert.equal(dashboard.run(`chart.data.datasets.find(item => item.seriesKey === '${series}').data.includes(null)`), true);
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
    const recent = dashboard.get('recent-events').children[0];
    assert.match(recent.textContent, /Heat-loss guard <b>active<\/b>.*Commanded 3.*Actual 1.*Target 2/);
    assert.equal(recent.innerHTML, '');
    assert.match(dashboard.get('recent-events').children[1].textContent, /Reason unavailable/);
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