import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import './index.css'
import App from './App.tsx'
import { appBasename } from './lib/appConfig'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {/* basename keeps client-side routes under the server's mount root (T-60):
        served at /shopping, "login" navigates to /shopping/login, not /login. */}
    <BrowserRouter basename={appBasename() || undefined}>
      <App />
    </BrowserRouter>
  </StrictMode>,
)
