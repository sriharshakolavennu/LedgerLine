import { useEffect, useState } from 'react'
import { Link, NavLink, Navigate, Route, Routes, useNavigate } from 'react-router-dom'
import { api } from './api.js'
import { useAuth } from './auth.jsx'
import ChainBackground from './ChainBackground.jsx'
import { AuditorPage, ChaosPage, Dashboard, HistoryPage, LedgerPage, LoginPage, MovePage, SignupPage, VerifyPage } from './pages.jsx'

function BooksPill() {
  const [s, setS] = useState(null)
  useEffect(() => {
    let live = true
    const poll = () => api('/audit/conservation').then((d) => live && setS(d)).catch(() => live && setS(false))
    poll()
    const t = setInterval(poll, 8000)
    return () => { live = false; clearInterval(t) }
  }, [])
  if (s === null) return <span className="pill">Checking books…</span>
  if (s === false) return <span className="pill bad">Books check unavailable</span>
  return (
    <span className={'pill ' + (s.balanced ? 'good' : 'bad')}
      title={`Debits ${s.debitsPaise} = Credits ${s.creditsPaise} paise · mismatched accounts: ${s.mismatchedAccounts}`}>
      <i className="dot" /> {s.balanced ? 'Books balanced ✓' : 'BOOKS UNBALANCED'}
    </span>
  )
}

function Layout({ children }) {
  const { user, logout } = useAuth()
  const nav = useNavigate()
  return (
    <>
      <header className="top">
        <Link to="/" className="brand">Ledger<b>Line</b> <small>Proof Desk</small></Link>
        <nav>
          <NavLink to="/" end>Dashboard</NavLink>
          {user.role !== 'AUDITOR' && <NavLink to="/move">Move money</NavLink>}
          <NavLink to="/history">History</NavLink>
          {user.role !== 'HOLDER' && <NavLink to="/audit">Audit</NavLink>}
          {user.role === 'ADMIN' && <NavLink to="/chaos">Chaos Lab</NavLink>}
          <NavLink to="/verify">Verify receipt</NavLink>
        </nav>
        <div className="right">
          <BooksPill />
          <span className={'role ' + user.role}>{user.role}</span>
          <span className="who">{user.email}</span>
          <button className="ghost" onClick={() => { logout(); nav('/login') }}>Log out</button>
        </div>
      </header>
      <main>{children}</main>
    </>
  )
}

function Guard({ children, roles }) {
  const { user } = useAuth()
  if (!user) return <Navigate to="/login" replace />
  if (roles && !roles.includes(user.role)) return <Navigate to="/" replace />
  return <Layout>{children}</Layout>
}

export default function App() {
  const { user } = useAuth()
  return (
    <>
      <ChainBackground />
      <Routes>
        <Route path="/login" element={user ? <Navigate to="/" replace /> : <LoginPage />} />
        <Route path="/signup" element={user ? <Navigate to="/" replace /> : <SignupPage />} />
        <Route path="/verify/:id?" element={user ? <Guard><VerifyPage /></Guard> : <VerifyPage standalone />} />
        <Route path="/" element={<Guard><Dashboard /></Guard>} />
        <Route path="/move" element={<Guard roles={['HOLDER', 'ADMIN']}><MovePage /></Guard>} />
        <Route path="/history" element={<Guard><HistoryPage /></Guard>} />
        <Route path="/ledger/:id" element={<Guard><LedgerPage /></Guard>} />
        <Route path="/audit" element={<Guard roles={['ADMIN', 'AUDITOR']}><AuditorPage /></Guard>} />
        <Route path="/chaos" element={<Guard roles={['ADMIN']}><ChaosPage /></Guard>} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </>
  )
}
