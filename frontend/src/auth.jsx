import { createContext, useContext, useState } from 'react'
import { api } from './api.js'

const Ctx = createContext(null)
export const useAuth = () => useContext(Ctx)

export function AuthProvider({ children }) {
  const [user, setUser] = useState(() => {
    try { return JSON.parse(sessionStorage.getItem('ll_user')) } catch { return null }
  })
  const login = async (email, password) => {
    const d = await api('/auth/login', { method: 'POST', body: { email, password } })
    sessionStorage.setItem('ll_token', d.token)
    sessionStorage.setItem('ll_user', JSON.stringify({ email: d.email, role: d.role, id: d.id }))
    setUser({ email: d.email, role: d.role, id: d.id })
  }
  const signup = async (email, password) => {
    const d = await api('/auth/signup', { method: 'POST', body: { email, password } })
    sessionStorage.setItem('ll_token', d.token)
    sessionStorage.setItem('ll_user', JSON.stringify({ email: d.email, role: d.role, id: d.id }))
    setUser({ email: d.email, role: d.role, id: d.id })
  }
  const logout = () => { sessionStorage.clear(); setUser(null) }
  return <Ctx.Provider value={{ user, login, signup, logout }}>{children}</Ctx.Provider>
}
