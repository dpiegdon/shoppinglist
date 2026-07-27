import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import './index.css'
import App from './App.tsx'
import { I18nProvider } from './i18n'
import { appBasename } from './lib/appConfig'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {/* Outside the router: the login page needs a language before any route or account exists,
        and the provider also owns <html lang/dir>, which is document-wide (T-111). */}
    <I18nProvider>
      {/* basename keeps client-side routes under the server's mount root (T-60):
          served at /shopping, "login" navigates to /shopping/login, not /login. */}
      <BrowserRouter basename={appBasename() || undefined}>
        <App />
      </BrowserRouter>
    </I18nProvider>
  </StrictMode>,
)
