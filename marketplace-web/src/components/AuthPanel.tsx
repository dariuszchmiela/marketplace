import { useState } from 'react'
import type { FormEvent } from 'react'
import { errorMessage } from '../api/client'
import { ErrorMessage } from './ErrorMessage'

interface AuthPanelProps {
  onLogin: (email: string, password: string) => Promise<void>
  onRegister: (email: string, password: string) => Promise<void>
  notice?: string | null
}

/** Deliberately plain: email + password, log in or create an account. */
export function AuthPanel({ onLogin, onRegister, notice }: AuthPanelProps) {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(action: (email: string, password: string) => Promise<void>) {
    setBusy(true)
    setError(null)
    const submittedPassword = password
    // The password is not kept in state once it has been sent (success or failure).
    setPassword('')
    try {
      await action(email, submittedPassword)
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  function handleLogin(event: FormEvent) {
    event.preventDefault()
    void submit(onLogin)
  }

  return (
    <section className="card auth">
      <h2>Log in to shop</h2>
      <p className="muted">Browse freely; the cart and checkout need an account.</p>
      {notice && <p className="warning">{notice}</p>}
      {error && <ErrorMessage message={error} onDismiss={() => setError(null)} />}
      <form className="auth-form" onSubmit={handleLogin}>
        <label>
          Email
          <input type="email" autoComplete="username" value={email} onChange={(event) => setEmail(event.target.value)} required />
        </label>
        <label>
          Password
          <input
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            required
            minLength={8}
          />
        </label>
        <div className="auth-actions">
          <button type="submit" className="primary" disabled={busy}>
            Log in
          </button>
          <button type="button" disabled={busy || !email || password.length < 8} onClick={() => void submit(onRegister)}>
            Create account
          </button>
        </div>
      </form>
    </section>
  )
}
