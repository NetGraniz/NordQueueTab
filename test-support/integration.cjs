'use strict'
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const net = require('node:net')
const { spawn } = require('node:child_process')
const mineflayer = require('mineflayer')
const root = path.resolve(process.argv[2] || '')
const java = process.argv[3]
assert(root.startsWith('C:\\Users\\artyo\\Documents\\Codex\\nordqueuetab-test-'))
assert(java)
const children = [], clients = [], results = []
let proxy, sequence = 0
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
async function until(fn, label, timeout = 20000) {
  const start = Date.now()
  while (!await fn()) {
    if (Date.now() - start > timeout) throw Error('Timeout: ' + label)
    await sleep(100)
  }
}
function start(name, jar) {
  const child = spawn(java, ['-Xms64M', '-Xmx256M', '-jar', jar],
    { cwd: path.join(root, name), windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] })
  const handle = { child, name, output: '', exited: false }; children.push(handle)
  for (const stream of [child.stdout, child.stderr]) stream.on('data', b => {
    handle.output += b.toString().replace(/\x1b\[[0-9;]*m/g, '')
  })
  child.on('exit', () => { handle.exited = true })
  child.on('error', e => { handle.output += String(e); handle.exited = true })
  return handle
}
function portReady(port) {
  return new Promise(resolve => {
    const socket = net.connect({ host: '127.0.0.1', port })
    socket.on('connect', () => { socket.destroy(); resolve(true) })
    socket.on('error', () => resolve(false))
  })
}
function command(text) { proxy.child.stdin.write(text + '\n') }
function pass(name) { results.push(name); console.log('PASS: ' + name) }
async function state() {
  const id = 't' + (++sequence), regex = new RegExp('TABSTATE ' + id + ' (\\{[^\\r\\n]+\\})')
  command('tabtest state ' + id)
  await until(() => regex.test(proxy.output), 'state ' + id, 5000)
  return JSON.parse(proxy.output.match(regex)[1])
}
async function waitState(predicate, label) {
  let latest
  await until(async () => { latest = await state(); return predicate(latest) }, label)
  return latest
}
function connect(name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25625, username: name,
    version: '26.2', auth: 'offline', hideErrors: true, checkTimeoutInterval: 30000 })
  const client = { name, bot, ended: false, tabPackets: 0, headers: [], errors: [] }; clients.push(client)
  bot.on('end', () => { client.ended = true })
  bot.on('error', e => client.errors.push(String(e)))
  bot._client.on('packet', (packet, meta) => {
    if (/player_info|playerlist_header/.test(meta.name)) client.tabPackets++
    if (/playerlist_header/.test(meta.name)) client.headers.push(JSON.stringify(packet))
  })
  return client
}
async function disconnect(c) {
  if (!c.ended) c.bot.quit()
  await until(() => c.ended, 'disconnect ' + c.name); await sleep(200)
}
const tabConfig = path.join(root, 'proxy', 'plugins', 'nordqueuetab', 'config.properties')
function config(cap, interval = 500) {
  fs.writeFileSync(tabConfig, `max-visible-players=${cap}\nupdate-interval-millis=${interval}\nheader=Position: <position> / <size> ETA: <estimate>\nfooter=LOCAL_ONLY\nplayer-format=<position> <player>\n`)
}
function fixture() {
  fs.writeFileSync(path.join(root, 'proxy', 'velocity.toml'), `config-version = "2.9"
bind = "127.0.0.1:25625"
motd = "LOCAL_TAB_TEST"
show-max-players = 1000
online-mode = false
force-key-authentication = false
player-info-forwarding-mode = "none"
forwarding-secret-file = "local-test-forwarding.secret"
[servers]
queue = "127.0.0.1:25627"
main = "127.0.0.1:25626"
try = ["queue"]
[forced-hosts]
"local.invalid" = ["queue"]
[advanced]
login-ratelimit = 0
connection-timeout = 2000
read-timeout = 10000
failover-on-unexpected-server-disconnect = true
`)
  for (const [name, port] of [['main', 25626], ['queue', 25627]]) {
    fs.writeFileSync(path.join(root, name, 'settings.yml'), `bind:
  ip: "127.0.0.1"
  port: ${port}
maxPlayers: -1
ping:
  description: "LOCAL_TEST"
  version: "LOCAL_TEST"
  protocol: -1
dimension: THE_END
gameMode: 3
secureProfile: false
playerList:
  enable: false
  username: "LocalTest"
headerAndFooter:
  enable: false
  header: ""
  footer: ""
brandName:
  enable: true
  content: "LOCAL_${name}"
infoForwarding:
  type: NONE
  secret: "unused-local-test"
joinMessage:
  enable: false
  text: ""
bossBar:
  enable: false
  text: ""
  health: 1.0
  color: BLUE
  division: SOLID
title:
  enable: false
  title: ""
  subtitle: ""
  fadeIn: 0
  stay: 20
  fadeOut: 0
readTimeout: 30000
logPlayersIp: false
debugLevel: 2
netty:
  transportType: NIO
  threads:
    bossGroup: 1
    workerGroup: 2
traffic:
  enable: false
  maxPacketSize: 8192
  interval: 7.0
  maxPacketRate: 500.0
  maxPacketBytesRate: 2048.0
`)
  }
  const q = path.join(root, 'proxy', 'plugins', 'nordqueue')
  fs.mkdirSync(q, { recursive: true })
  fs.mkdirSync(path.dirname(tabConfig), { recursive: true })
  fs.writeFileSync(path.join(q, 'config.properties'), `main-capacity=1\nminimum-wait-seconds=0\ntransfer-interval-seconds=1\nfailed-retry-seconds=5\nconnection-attempt-timeout-seconds=30\ndisplay-interval-millis=1000\n`)
  fs.writeFileSync(path.join(q, 'priority-players.txt'), 'TabPriority\n')
  config(3)
}
async function main() {
  for (const p of [25625, 25626, 25627]) assert(!await portReady(p), 'Test port already occupied: ' + p)
  fixture(); start('main', 'NanoLimbo.jar'); start('queue', 'NanoLimbo.jar')
  await until(async () => await portReady(25626) && await portReady(25627), 'local backends')
  proxy = start('proxy', 'velocity.jar')
  await until(() => /LOCAL_TAB_PROBE_READY/.test(proxy.output) || proxy.exited, 'local Velocity probe')
  assert(!proxy.exited, proxy.output)
  assert.match(proxy.output, /NordQueueTab 1.1.0 enabled/)
  const keeper = connect('TabKeeper')
  await waitState(s => s.tabs.TabKeeper?.server === 'main', 'main occupied')
  const waiting = []
  for (let i = 0; i < 5; i++) {
    const c = connect('TabWait' + i); waiting.push(c)
    await waitState(s => s.waiting.includes(c.name), c.name + ' queued')
  }
  const initial = await waitState(s => s.viewers === 5 && s.owned === 15, 'bounded rows populated')
  for (const c of waiting) assert.equal(initial.tabs[c.name].entries.length, 3)
  assert.deepEqual(initial.tabs.TabWait4.entries, ['TabWait0', 'TabWait1', 'TabWait4'])
  for (const c of waiting) assert(initial.tabs[c.name].self)
  pass('Real clients: three-row cap, leading players and self on later pages')
  await until(() => Object.keys(waiting[4].bot.players).includes('TabWait4'), 'client receives own row')
  assert(Object.keys(waiting[4].bot.players).includes('TabWait0'))
  assert(!Object.keys(waiting[4].bot.players).includes('TabWait3'))
  await until(() => waiting[4].headers.some(h => h.includes('depends on available slots')), 'honest ETA header')
  pass('Client receives bounded TAB and non-promissory waiting-time text')
  await sleep(1000)
  const packetCounts = waiting.map(c => c.tabPackets)
  await sleep(2000)
  assert.deepEqual(waiting.map(c => c.tabPackets), packetCounts)
  pass('Unchanged queue: no TAB/header packets across four refreshes')
  command('tabtest foreign TabWait4')
  await waitState(s => s.tabs.TabWait4.entries.includes('ForeignRow'), 'foreign row injected')
  config(1); command('nordqueuetab reload')
  const shrunk = await waitState(s => s.cap === 1 && s.owned === 5, 'cap shrink')
  assert.deepEqual(shrunk.tabs.TabWait4.entries, ['TabWait4', 'ForeignRow'])
  pass('Valid reload shrinks own rows while preserving a foreign row')
  config(81); command('nordqueuetab reload'); await sleep(1200)
  const bad = await state(); assert.equal(bad.cap, 1); assert.equal(bad.owned, 5)
  assert.match(proxy.output, /Reload failed; previous settings are still active/)
  pass('Invalid reload reports failure and retains working configuration')
  config(3); for (let i = 0; i < 8; i++) command('nordqueuetab reload')
  const reloadState = await waitState(s => s.cap === 3 && s.owned === 15, 'repeated reloads')
  assert.equal(reloadState.tasks, 1, 'Exactly one live TAB task must remain')
  await sleep(1000)
  const count = waiting[4].tabPackets; await sleep(1500)
  assert.equal(waiting[4].tabPackets, count)
  pass('Repeated reloads do not leave duplicate refresh tasks or redundant packets')
  const priority = connect('TabPriority')
  const prioritized = await waitState(s => s.waiting[0] === priority.name && s.viewers === 6 && s.owned === 18, 'priority in first position')
  assert.deepEqual(prioritized.tabs.TabWait4.entries, ['TabPriority', 'TabWait0', 'TabWait4', 'ForeignRow'])
  pass('Priority ordering is reflected consistently in the combined TAB')
  await disconnect(priority)
  await waitState(s => s.viewers === 5 && s.owned === 15 && !s.waiting.includes(priority.name), 'priority cleanup')
  const old = waiting.pop(); await disconnect(old)
  await waitState(s => s.viewers === 4 && s.owned === 12, 'old session cleanup')
  const newer = connect(old.name); waiting.push(newer)
  const rejoined = await waitState(s => s.viewers === 5 && s.owned === 15, 'same UUID rejoin')
  assert(!rejoined.tabs[newer.name].entries.includes('ForeignRow'))
  assert(rejoined.tabs[newer.name].self)
  pass('Disconnect/reconnect with the same UUID releases old state and starts a fresh view')
  await disconnect(keeper)
  const admitted = await waitState(s => s.tabs.TabWait0?.server === 'main' && s.viewers === 4, 'queued player admitted')
  assert.equal(admitted.tabs.TabWait0.entries.length, 0)
  pass('Admission to main removes owned queue rows and viewer state')
  for (const c of waiting) await disconnect(c)
  await waitState(s => s.viewers === 0 && s.owned === 0 && Object.keys(s.tabs).length === 0, 'final release')
  assert(!/NoSuchMethodError|ClassCastException|ConcurrentModificationException|Queue TAB refresh failed/.test(proxy.output))
  pass('All clients leaving releases every viewer and owned row without API/concurrency errors')
  // Separate local start with the old API: the new TAB must decline to start, not crash the proxy.
  command('shutdown'); await until(() => proxy.exited, 'first proxy stopped')
  const legacy = start('legacy-proxy', 'velocity.jar')
  await until(() => /NordQueue snapshot API is unavailable/.test(legacy.output) || legacy.exited, 'old API guard')
  assert(!legacy.exited, legacy.output)
  await until(() => portReady(25625), 'legacy proxy still starts')
  assert(!/Couldn.t (?:load|pass)|NoClassDefFoundError|NoSuchMethodError/.test(legacy.output), legacy.output)
  legacy.child.stdin.write('shutdown\n'); await until(() => legacy.exited, 'legacy proxy stopped')
  pass('Old NordQueue API: TAB declines to start clearly while Velocity remains operational')
  fs.writeFileSync(path.join(root, 'integration-results.json'), JSON.stringify({ total: results.length, passed: results,
    fixture: 'loopback only: Velocity 4.2.1 + two NanoLimbo + NordQueue 1.1.1; 7 synthetic 26.2 clients, not 1000 real connections' }, null, 2))
  console.log('ALL ' + results.length + ' LOCAL TAB INTEGRATION SCENARIOS PASSED')
}
main().catch(e => { console.error(e.stack); process.exitCode = 1 }).finally(async () => {
  for (const c of clients) if (!c.ended) c.bot.quit()
  if (proxy && !proxy.exited) {
    command('shutdown'); await until(() => proxy.exited, 'proxy shutdown', 15000).catch(() => proxy.child.kill())
  }
  for (const h of children) {
    if (!h.exited) h.child.kill()
    fs.writeFileSync(path.join(root, h.name + '-test-output.log'), h.output)
  }
})
