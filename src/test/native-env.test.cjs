// Keeps the three places that define an option's default in step: ha_addon/config.json (the source of truth),
// native/genvex.env.example, ha_addon/run.sh and HumidityMonitor.java.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const root = path.resolve(__dirname, '..', '..');
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8');
const options = Object.entries(JSON.parse(read('ha_addon/config.json')).options);
const envName = key => key.toUpperCase();
// 22.0 and "22" are the same default, true and "true" too.
const normalise = value => {
    const text = String(value).trim();
    return /^-?\d+(\.\d+)?$/.test(text) ? String(Number(text)) : text;
};
// The unit's address and account are per-installation, so the examples ship them empty.
const EMPTY_DEFAULTS = new Set(['genvex_ip', 'genvex_email']);
// Hosting variables that exist only outside the add-on; they have no config.json option.
const HOSTING_ONLY = new Set(['GENVEX_LATITUDE', 'GENVEX_LONGITUDE']);

const hasJq = spawnSync('jq', ['--version']).status === 0;

function activeEnvLines(text) {
    const values = new Map();
    for (const line of text.split('\n')) {
        const match = /^([A-Z][A-Z0-9_]*)=(.*)$/.exec(line);
        if (!match) continue;
        assert.ok(!values.has(match[1]), `${match[1]} is defined twice`);
        values.set(match[1], match[2]);
    }
    return values;
}

test('config.json has the 35 options the native env file must cover', () => {
    assert.equal(options.length, 35);
});

test('env example has every add-on option with the add-on default', () => {
    const env = activeEnvLines(read('native/genvex.env.example'));
    for (const [key, expected] of options) {
        const name = envName(key);
        assert.ok(env.has(name), `${name} is missing from native/genvex.env.example`);
        const want = EMPTY_DEFAULTS.has(key) ? '' : expected;
        assert.equal(normalise(env.get(name)), normalise(want), `${name} default differs from config.json`);
    }
});

test('env example has no active setting that is not an add-on option', () => {
    const known = new Set([...options.map(([key]) => envName(key)), ...HOSTING_ONLY]);
    for (const name of activeEnvLines(read('native/genvex.env.example')).keys()) {
        assert.ok(known.has(name), `${name} is not a config.json option`);
    }
});

test('env example ships no secret and leaves the sunset coordinates for the operator', () => {
    const env = activeEnvLines(read('native/genvex.env.example'));
    for (const name of ['GENVEX_IP', 'GENVEX_EMAIL', 'GENVEX_LATITUDE', 'GENVEX_LONGITUDE']) {
        assert.equal(env.get(name), '', `${name} must be empty in the example`);
    }
});

test('run.sh falls back to the add-on defaults when options.json sets nothing', { skip: !hasJq && 'jq is not installed' }, () => {
    // Run the real script with an empty options.json: jq is fed {} and the script prints its environment
    // instead of starting java.
    const script = read('ha_addon/run.sh')
        .replace('[ -f "$CONFIG_PATH" ]', 'true')
        .replace(/^exec java .*$/m, 'env');
    const wrapped = 'jq() { command jq "${@:1:$#-1}" <<< \'{}\'; }\n' + script;
    const run = spawnSync('bash', ['-c', wrapped], { env: { PATH: process.env.PATH }, encoding: 'utf8' });
    assert.equal(run.status, 0, run.stderr);
    const env = activeEnvLines(run.stdout.split('\n').filter(line => !line.startsWith(' ')).join('\n'));
    for (const [key, expected] of options) {
        const name = envName(key);
        assert.ok(env.has(name), `run.sh does not export ${name}`);
        assert.equal(normalise(env.get(name)), normalise(expected), `run.sh default for ${name} differs from config.json`);
    }
});

test('HumidityMonitor defaults match config.json', () => {
    const source = read('src/main/java/HumidityMonitor.java');
    const direct = name => new RegExp(`getOrDefault\\(\\s*"${name}"\\s*,\\s*"([^"]*)"\\s*\\)`).exec(source);
    const defaults = new Map();
    for (const [key] of options) {
        const name = envName(key);
        const match = direct(name);
        if (match) defaults.set(name, match[1]);
    }
    // These two derive from COOLING_TARGET_TEMP, the pre-1.70 name that is still accepted.
    const stop = /getOrDefault\(\s*"COOLING_STOP_TEMP"\s*,\s*System\.getenv\(\)\.getOrDefault\("COOLING_TARGET_TEMP"\s*,\s*"([^"]*)"\)\)/.exec(source);
    assert.ok(stop, 'COOLING_STOP_TEMP default expression changed; update this test');
    defaults.set('COOLING_STOP_TEMP', stop[1]);
    assert.ok(/"COOLING_START_TEMP"\s*,\s*String\.valueOf\(COOLING_STOP_TEMP \+ 0\.5\)/.test(source),
        'COOLING_START_TEMP default expression changed; update this test');
    defaults.set('COOLING_START_TEMP', String(Number(stop[1]) + 0.5));

    for (const [key, expected] of options) {
        const name = envName(key);
        assert.ok(defaults.has(name), `HumidityMonitor.java has no default for ${name}`);
        assert.equal(normalise(defaults.get(name)), normalise(expected), `Java default for ${name} differs from config.json`);
    }
});

test('publish.sh stages the native directory', () => {
    assert.match(read('publish.sh'), /git add [^\n]*native\//s);
});
