import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, newKey, rupees, short, toPaise } from './api.js'
import { useAuth } from './auth.jsx'

/* ---------- small helpers ---------- */
function useCountUp(target) {
  const [v, setV] = useState(target)
  const from = useRef(target)
  useEffect(() => {
    const start = performance.now(), a = from.current, d = 600
    let raf
    const step = (t) => {
      const k = Math.min(1, (t - start) / d)
      setV(Math.round(a + (target - a) * (1 - Math.pow(1 - k, 3))))
      if (k < 1) raf = requestAnimationFrame(step); else from.current = target
    }
    raf = requestAnimationFrame(step)
    return () => cancelAnimationFrame(raf)
  }, [target])
  return v
}

const Money = ({ p }) => <span className="mono amt">{rupees(useCountUp(p))}</span>

function Hash({ h }) {
  const [ok, setOk] = useState(false)
  if (!h) return null
  return (
    <button className="hash mono" title={h} onClick={() => { navigator.clipboard?.writeText(h); setOk(true); setTimeout(() => setOk(false), 1200) }}>
      ⛓ {ok ? 'copied' : short(h)}
    </button>
  )
}

const Err = ({ e }) => (e ? <div className="alert" role="alert"><b>{e.code}</b> · {e.message}</div> : null)

/* ---------- login ---------- */
export function LoginPage() {
  const { login } = useAuth()
  const [email, setEmail] = useState('alice@ledgerline.dev')
  const [password, setPassword] = useState('Passw0rd!')
  const [err, setErr] = useState(null)
  const [busy, setBusy] = useState(false)
  const demo = ['admin', 'auditor', 'alice', 'bob']
  const submit = async (ev) => {
    ev.preventDefault(); setBusy(true); setErr(null)
    try { await login(email, password) } catch (e) { setErr(e) } finally { setBusy(false) }
  }
  return (
    <div className="center">
      <form className="card glass login" onSubmit={submit}>
        <h1 className="brand big">Ledger<b>Line</b></h1>
        <p className="muted">Money movement with a ledger you can prove.</p>
        <label>Email<input value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" /></label>
        <label>Password<input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" /></label>
        <Err e={err} />
        <button className="primary" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
        <div className="chips">
          <span className="muted">Demo:</span>
          {demo.map((d) => <button type="button" key={d} className="chip" onClick={() => setEmail(d + '@ledgerline.dev')}>{d}</button>)}
        </div>
        <Link className="small" to="/signup">New here? Create an account</Link>
        <Link className="muted small" to="/verify">Verify a receipt without logging in →</Link>
      </form>
    </div>
  )
}

/* ---------- signup ---------- */
export function SignupPage() {
  const { signup } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [err, setErr] = useState(null)
  const [busy, setBusy] = useState(false)
  const submit = async (ev) => {
    ev.preventDefault(); setErr(null)
    if (password !== confirm) return setErr({ code: 'PASSWORD_MISMATCH', message: 'The two passwords do not match.' })
    setBusy(true)
    try { await signup(email, password) } catch (e) { setErr(e); setBusy(false) }
  }
  return (
    <div className="center">
      <form className="card glass login" onSubmit={submit}>
        <h1 className="brand big">Ledger<b>Line</b></h1>
        <p className="muted">Create your account. A wallet is opened for you straight away.</p>
        <label>Email<input type="email" required maxLength={120} value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" /></label>
        <label>Password<input type="password" required minLength={8} maxLength={72} value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" /></label>
        <label>Confirm password<input type="password" required value={confirm} onChange={(e) => setConfirm(e.target.value)} autoComplete="new-password" /></label>
        <span className="muted small">At least 8 characters, with a letter and a digit.</span>
        <Err e={err} />
        <button className="primary" disabled={busy}>{busy ? 'Creating account…' : 'Create account'}</button>
        <Link className="muted small" to="/login">Already registered? Sign in</Link>
      </form>
    </div>
  )
}

/* ---------- transaction history ---------- */
const when = (ms) => new Date(Number(ms)).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' })

function TxnTable({ rows, showOwner }) {
  return (
    <div className="card glass scroll">
      <table>
        <thead><tr><th>When</th><th>Details</th>{showOwner && <th>Owner</th>}<th>Account</th><th>Amount</th><th>Balance after</th><th>Receipt</th></tr></thead>
        <tbody>
          {rows.map((r) => {
            const credit = r.direction === 'CREDIT'
            return (
              <tr key={r.entryId}>
                <td className="small muted">{when(r.tsMs)}</td>
                <td>{r.description}</td>
                {showOwner && <td className="small muted">{r.ownerEmail}</td>}
                <td className="small">#{r.accountId} {r.accountName}</td>
                <td className={'mono ' + (credit ? 'good-t' : 'bad-t')}>{credit ? '+' : '−'}{rupees(r.amountPaise)}</td>
                <td className="mono">{rupees(r.balanceAfterPaise)}</td>
                <td><Link to={`/verify/${r.receiptId}`} className="mono small">{short(r.receiptId)}</Link></td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

function RecentActivity() {
  const { user } = useAuth()
  const [rows, setRows] = useState(null)
  useEffect(() => { api('/transactions?limit=5').then(setRows).catch(() => setRows([])) }, [])
  if (!rows) return null
  return (
    <>
      <div className="row"><h3>Recent activity</h3><Link to="/history" className="small">View full history</Link></div>
      {rows.length ? <TxnTable rows={rows} showOwner={user.role !== 'HOLDER'} />
                   : <p className="muted">No activity yet. Open Move money and add your first deposit.</p>}
    </>
  )
}

const plainAmount = (p) => `${Math.floor(p / 100)}.${String(p % 100).padStart(2, '0')}`   // integer paise -> "123.45", no float maths
const csvCell = (v) => {
  let s = String(v ?? '')
  if (/^[=+\-@]/.test(s)) s = "'" + s                 // stop spreadsheet formula injection
  return '"' + s.replaceAll('"', '""') + '"'
}

export function HistoryPage() {
  const { user } = useAuth()
  const [rows, setRows] = useState(null)
  const [accts, setAccts] = useState([])
  const [acct, setAcct] = useState('')
  const [flow, setFlow] = useState('all')
  const [err, setErr] = useState(null)
  useEffect(() => { api('/accounts').then((a) => setAccts(a.filter((x) => x.type === 'USER'))).catch(setErr) }, [])
  useEffect(() => {
    setRows(null)
    api('/transactions?limit=200' + (acct ? '&accountId=' + acct : '')).then(setRows).catch(setErr)
  }, [acct])

  const shown = (rows || []).filter((r) => flow === 'all' || (flow === 'in') === (r.direction === 'CREDIT'))
  const total = (dir) => shown.filter((r) => r.direction === dir).reduce((s, r) => s + r.amountPaise, 0)

  const download = () => {
    const head = ['Date', 'Details', 'Account', 'Amount (INR)', 'Balance after (INR)', 'Receipt ID']
    const lines = shown.map((r) => [new Date(Number(r.tsMs)).toISOString(), r.description, `#${r.accountId} ${r.accountName}`,
      (r.direction === 'CREDIT' ? '' : '-') + plainAmount(r.amountPaise), plainAmount(r.balanceAfterPaise), r.receiptId].map(csvCell).join(','))
    const url = URL.createObjectURL(new Blob([[head.map(csvCell).join(','), ...lines].join('\n')], { type: 'text/csv' }))
    const a = document.createElement('a')
    a.href = url; a.download = 'ledgerline-statement.csv'; a.click()
    URL.revokeObjectURL(url)
  }

  return (
    <>
      <div className="row"><h2>Transaction history</h2>
        <button className="ghost" onClick={download} disabled={!shown.length}>Download CSV</button>
      </div>
      <Err e={err} />
      <div className="card glass row" style={{ flexWrap: 'wrap' }}>
        <label>Account
          <select value={acct} onChange={(e) => setAcct(e.target.value)}>
            <option value="">All accounts</option>
            {accts.map((a) => <option key={a.id} value={a.id}>#{a.id} · {a.name}</option>)}
          </select>
        </label>
        <div className="tabs" role="tablist">
          {[['all', 'All'], ['in', 'Money in'], ['out', 'Money out']].map(([k, l]) => (
            <button type="button" role="tab" aria-selected={flow === k} key={k} className={flow === k ? 'tab on' : 'tab'} onClick={() => setFlow(k)}>{l}</button>
          ))}
        </div>
        <div><span className="muted small">Money in</span><div className="mono good-t">{rupees(total('CREDIT'))}</div></div>
        <div><span className="muted small">Money out</span><div className="mono bad-t">{rupees(total('DEBIT'))}</div></div>
      </div>
      {rows === null && !err && <p className="muted">Loading history…</p>}
      {rows && shown.length > 0 && <TxnTable rows={shown} showOwner={user.role !== 'HOLDER'} />}
      {rows && shown.length === 0 && <p className="muted">Nothing to show here yet. Deposits, withdrawals and transfers will appear as soon as they are posted.</p>}
      {rows && rows.length >= 200 && <p className="muted small">Showing the latest 200 entries.</p>}
    </>
  )
}

/* ---------- dashboard ---------- */
function AccountCard({ a }) {
  return (
    <Link to={`/ledger/${a.id}`} className="card glass acct">
      <div className="row"><span className="muted">#{a.id} · {a.type}</span>{a.ownerEmail && <span className="muted small">{a.ownerEmail}</span>}</div>
      <h3>{a.name}</h3>
      <Money p={a.balancePaise} />
    </Link>
  )
}

export function Dashboard() {
  const { user } = useAuth()
  const [accts, setAccts] = useState(null)
  const [err, setErr] = useState(null)
  useEffect(() => { api('/accounts').then(setAccts).catch(setErr) }, [])
  const total = (accts || []).filter((a) => a.type === 'USER').reduce((s, a) => s + a.balancePaise, 0)
  return (
    <>
      <h2>Welcome, {user.email.split('@')[0]}</h2>
      <Err e={err} />
      {accts && (
        <div className="card glass hero">
          <span className="muted">{user.role === 'HOLDER' ? 'Your total balance' : 'Total in user accounts'}</span>
          <Money p={total} />
        </div>
      )}
      <div className="grid">{(accts || []).map((a) => <AccountCard key={a.id} a={a} />)}</div>
      {accts && accts.length === 0 && <p className="muted">No accounts yet.</p>}
      <RecentActivity />
    </>
  )
}

/* ---------- move money ---------- */
export function MovePage() {
  const [accts, setAccts] = useState([])
  const [mode, setMode] = useState('deposit')
  const [acct, setAcct] = useState('')
  const [to, setTo] = useState('')
  const [amount, setAmount] = useState('')
  const [key, setKey] = useState(newKey())
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState(null)
  const [done, setDone] = useState(null)
  const inflight = useRef(false)
  // A new Idempotency-Key whenever the user edits the request: a key must only ever map to one request.
  const edit = (setter) => (e) => { setter(e.target.value); setKey(newKey()) }

  useEffect(() => {
    api('/accounts').then((a) => { const u = a.filter((x) => x.type === 'USER'); setAccts(u); if (u[0]) setAcct(String(u[0].id)) }).catch(setErr)
  }, [])

  const submit = async (ev) => {
    ev.preventDefault()
    if (inflight.current) return // double-click guard
    const paise = toPaise(amount)
    if (!paise || paise > 1_000_000_000) return setErr({ code: 'INVALID_AMOUNT', message: 'Enter an amount between ₹0.01 and ₹10,000,000.00, e.g. 250 or 250.50' })
    if (mode === 'transfer' && !/^\d+$/.test(to)) return setErr({ code: 'INVALID_ACCOUNT', message: 'Enter the destination account number' })
    inflight.current = true; setBusy(true); setErr(null); setDone(null)
    try {
      const body = mode === 'transfer' ? { toAccountId: Number(to), amountPaise: paise } : { amountPaise: paise }
      const r = await api(`/accounts/${acct}/${mode}`, { method: 'POST', body, idem: key })
      setDone(r); setKey(newKey()); setAmount('')
      api('/accounts').then((a) => setAccts(a.filter((x) => x.type === 'USER')))
    } catch (e) {
      setErr(e) // key kept: a retry of the SAME request can never double-pay
    } finally { inflight.current = false; setBusy(false) }
  }

  return (
    <>
      <h2>Move money</h2>
      <form className="card glass form" onSubmit={submit}>
        <div className="tabs" role="tablist">
          {['deposit', 'withdraw', 'transfer'].map((m) => (
            <button type="button" role="tab" aria-selected={mode === m} key={m} className={mode === m ? 'tab on' : 'tab'} onClick={() => { setMode(m); setKey(newKey()); setErr(null); setDone(null) }}>{m}</button>
          ))}
        </div>
        <label>{mode === 'transfer' ? 'From account' : 'Account'}
          <select value={acct} onChange={edit(setAcct)}>
            {accts.map((a) => <option key={a.id} value={a.id}>#{a.id} · {a.name} · {rupees(a.balancePaise)}</option>)}
          </select>
        </label>
        {mode === 'transfer' && <label>Destination account number<input inputMode="numeric" value={to} onChange={edit(setTo)} placeholder="e.g. 5" /></label>}
        <label>Amount (₹)<input inputMode="decimal" value={amount} onChange={edit(setAmount)} placeholder="250.00" /></label>
        <div className="muted small">Request ID <span className="chip mono">{key.slice(0, 8)}</span> — sent as Idempotency-Key; retrying this exact request can never pay twice.</div>
        <Err e={err} />
        <button className="primary" disabled={busy}>{busy ? 'Processing…' : `Confirm ${mode}`}</button>
      </form>
      {done && (
        <div className="card glass ok">
          <b>{done.type} posted ✓</b> {done.replayed && <span className="chip">replayed</span>}
          <div>New balance: <span className="mono">{rupees(done.balanceAfterPaise)}</span></div>
          <div className="small">Receipt: <Link to={`/verify/${done.receiptId}`} className="mono">{done.receiptId}</Link></div>
        </div>
      )}
    </>
  )
}

/* ---------- ledger explorer ---------- */
export function LedgerPage() {
  const { id } = useParams()
  const { user } = useAuth()
  const [rows, setRows] = useState(null)
  const [chain, setChain] = useState(null)
  const [err, setErr] = useState(null)
  useEffect(() => { api(`/accounts/${id}/entries`).then(setRows).catch(setErr) }, [id])
  const verify = () => api(`/audit/verify-chain/${id}`).then(setChain).catch(setErr)
  return (
    <>
      <div className="row"><h2>Ledger · account #{id}</h2>
        {user.role !== 'HOLDER' && <button className="ghost" onClick={verify}>Verify hash chain</button>}
      </div>
      <Err e={err} />
      {chain && <div className={'alert ' + (chain.intact ? 'okbox' : '')}>{chain.intact ? `Chain intact ✓ (${chain.entries} entries)` : `Chain BROKEN at entry #${chain.brokenAtEntryId}`}</div>}
      <div className="card glass scroll">
        <table>
          <thead><tr><th>#</th><th>Type</th><th>Amount</th><th>Balance after</th><th>Hash chain</th><th>Time</th></tr></thead>
          <tbody>
            {(rows || []).map((r) => (
              <tr key={r.id}>
                <td className="mono">{r.id}</td>
                <td><span className={'dir ' + r.direction}>{r.direction === 'CREDIT' ? '+ credit' : '− debit'}</span></td>
                <td className="mono">{rupees(r.amountPaise)}</td>
                <td className="mono">{rupees(r.balanceAfterPaise)}</td>
                <td><Hash h={r.prevHash === 'GENESIS' ? null : r.prevHash} /> <span className="muted">→</span> <Hash h={r.entryHash} /></td>
                <td className="small muted">{new Date(r.tsMs).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {rows && rows.length === 0 && <p className="muted">No entries yet.</p>}
      </div>
    </>
  )
}

/* ---------- auditor ---------- */
export function AuditorPage() {
  const [cons, setCons] = useState(null)
  const [accts, setAccts] = useState([])
  const [log, setLog] = useState([])
  const [res, setRes] = useState({})
  const [err, setErr] = useState(null)
  useEffect(() => {
    api('/audit/conservation').then(setCons).catch(setErr)
    api('/accounts').then(setAccts).catch(setErr)
    api('/audit/log').then(setLog).catch(setErr)
  }, [])
  const verify = (id) => api(`/audit/verify-chain/${id}`).then((r) => setRes((p) => ({ ...p, [id]: r }))).catch(setErr)
  return (
    <>
      <h2>Audit</h2>
      <Err e={err} />
      {cons && (
        <div className={'card glass ' + (cons.balanced ? 'ok' : 'badcard')}>
          <b>{cons.balanced ? 'Books balanced ✓' : 'Books UNBALANCED'}</b>
          <div className="mono small">Debits {rupees(cons.debitsPaise)} · Credits {rupees(cons.creditsPaise)} · Net of all balances {rupees(cons.totalBalancePaise)} · Mismatched accounts {cons.mismatchedAccounts}</div>
        </div>
      )}
      <h3>Hash-chain verification</h3>
      <div className="card glass scroll"><table>
        <thead><tr><th>Account</th><th>Owner</th><th>Balance</th><th></th><th>Result</th></tr></thead>
        <tbody>{accts.map((a) => (
          <tr key={a.id}>
            <td>#{a.id} {a.name}</td><td className="small muted">{a.ownerEmail || 'system'}</td><td className="mono">{rupees(a.balancePaise)}</td>
            <td><button className="ghost" onClick={() => verify(a.id)}>Verify</button></td>
            <td>{res[a.id] && (res[a.id].intact ? <span className="good-t">intact ✓ ({res[a.id].entries})</span> : <span className="bad-t">broken at #{res[a.id].brokenAtEntryId}</span>)}</td>
          </tr>))}</tbody>
      </table></div>
      <h3>Audit log (latest 100)</h3>
      <div className="card glass scroll"><table>
        <thead><tr><th>#</th><th>Action</th><th>Entity</th><th>Actor</th><th>When</th></tr></thead>
        <tbody>{log.map((l) => (
          <tr key={l.id}><td className="mono">{l.id}</td><td>{l.action}</td><td className="mono small">{l.entity} {short(l.entityId)}</td><td>{l.actorUserId ?? '—'}</td><td className="small muted">{String(l.createdAt).replace('T', ' ').slice(0, 19)}</td></tr>))}</tbody>
      </table></div>
    </>
  )
}

/* ---------- chaos lab ---------- */
const SCENARIOS = [
  { id: 'overdraw', title: 'Simultaneous withdrawals', text: '20 parallel withdrawals of ₹100 against a ₹500 balance.' },
  { id: 'duplicate', title: 'Duplicate request storm', text: 'The same Idempotency-Key sent 10 times at once.' },
  { id: 'storm', title: 'Transfer storm', text: '50 random parallel transfers across 5 accounts.' },
]

export function ChaosPage() {
  const [out, setOut] = useState({})
  const [busy, setBusy] = useState(null)
  const [err, setErr] = useState(null)
  const run = async (id) => {
    setBusy(id); setErr(null)
    try { const r = await api(`/chaos/run/${id}`, { method: 'POST' }); setOut((p) => ({ ...p, [id]: r })) }
    catch (e) { setErr(e) } finally { setBusy(null) }
  }
  return (
    <>
      <h2>Chaos Lab</h2>
      <p className="muted">Real concurrent requests against the real ledger. Dev profile, admin only.</p>
      <Err e={err} />
      <div className="grid">
        {SCENARIOS.map((s) => {
          const r = out[s.id]
          return (
            <div key={s.id} className="card glass">
              <h3>{s.title}</h3>
              <p className="muted">{s.text}</p>
              <button className="primary" disabled={!!busy} onClick={() => run(s.id)}>{busy === s.id ? 'Running…' : 'Run'}</button>
              {r && (
                <div className="result">
                  <div className={r.ok ? 'good-t' : 'bad-t'}><b>{r.ok ? 'PASSED ✓' : 'FAILED ✗'}</b></div>
                  <div className="meter">
                    <div><span className="muted small">before</span><div className="mono">{rupees(r.beforePaise)}</div></div>
                    <div>→</div>
                    <div><span className="muted small">after</span><div className="mono">{rupees(r.afterPaise)}</div></div>
                  </div>
                  <table><tbody>{Object.entries(r.outcomes).map(([k, v]) => <tr key={k}><td className="mono small">{k}</td><td className="mono">{v}</td></tr>)}</tbody></table>
                  {r.transactionsCreated != null && <div className="small">Transactions created: <b>{r.transactionsCreated}</b></div>}
                  <div className="small muted">Books balanced afterwards: {String(r.conservation.balanced)}</div>
                </div>
              )}
            </div>
          )
        })}
      </div>
    </>
  )
}

/* ---------- public verify ---------- */
export function VerifyPage({ standalone }) {
  const { id } = useParams()
  const nav = useNavigate()
  const [val, setVal] = useState(id || '')
  const [res, setRes] = useState(null)
  const [err, setErr] = useState(null)
  useEffect(() => {
    setRes(null); setErr(null)
    if (id) api(`/public/receipts/${id}`).then(setRes).catch(setErr)
  }, [id])
  return (
    <div className={standalone ? 'center' : ''}>
      <div className="card glass verify">
        <h2>Verify a receipt</h2>
        <p className="muted">Anyone can check that a transaction exists and its hash chain is intact. No personal data is shown.</p>
        <form className="inline" onSubmit={(e) => { e.preventDefault(); nav(`/verify/${val.trim()}`) }}>
          <input value={val} onChange={(e) => setVal(e.target.value)} placeholder="Receipt ID (UUID)" className="mono" />
          <button className="primary">Verify</button>
        </form>
        <Err e={err} />
        {res && (
          <div className={'bigresult ' + (res.intact ? 'good' : 'bad')}>
            <div className="huge">{res.intact ? '✓ Hash chain intact' : '✗ Chain broken'}</div>
            <div className="small">{res.type} · {res.entryCount} ledger entries · {res.createdAt}</div>
            {res.entryHashes.map((h) => <Hash key={h} h={h} />)}
          </div>
        )}
        {standalone && <Link to="/login" className="muted small">← Back to sign in</Link>}
      </div>
    </div>
  )
}
