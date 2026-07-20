import { useState, type FormEvent } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { homePathForRole } from '../auth/roleRoutes';
import { useI18n } from '../i18n/I18nContext';
import { LanguageToggle } from '../components/LanguageToggle';

export function LoginPage() {
  const { user, login } = useAuth();
  const { t } = useI18n();
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  if (user) {
    return <Navigate to={homePathForRole(user.role)} replace />;
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError(false);
    setSubmitting(true);
    try {
      const loggedIn = await login(email, password);
      navigate(homePathForRole(loggedIn.role), { replace: true });
    } catch {
      setError(true);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="auth">
      <form className="auth__card" onSubmit={onSubmit}>
        <div className="auth__brand">{t('app.name')}</div>
        <h1>{t('login.title')}</h1>
        {error && <div className="banner banner--error">{t('login.error')}</div>}
        <label className="field">
          <span className="field__label">{t('common.email')}</span>
          <input
            className="input"
            type="email"
            value={email}
            autoComplete="username"
            onChange={(e) => setEmail(e.target.value)}
            required
          />
        </label>
        <label className="field">
          <span className="field__label">{t('common.password')}</span>
          <input
            className="input"
            type="password"
            value={password}
            autoComplete="current-password"
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </label>
        <button className="btn btn--block" type="submit" disabled={submitting}>
          {t('login.submit')}
        </button>
        <p className="muted center" style={{ marginTop: 16, fontSize: 14 }}>
          <Link to="/activate">{t('activate.title')}</Link>
        </p>
        <div className="center" style={{ marginTop: 8 }}>
          <LanguageToggle />
        </div>
      </form>
    </div>
  );
}
