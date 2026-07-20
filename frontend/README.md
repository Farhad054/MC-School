# MC-School Frontend

React + TypeScript (Vite) web client for the Mindcraft School flashcard app. See the
[root README](../README.md) for the full product and run instructions.

## Quick start

```bash
npm install
cp .env.example .env.local     # VITE_API_BASE_URL → backend base URL
npm run dev                    # http://localhost:5173
npm run build                  # type-check + production build into dist/
```

## Structure

```
src/
├── api/         REST client (client.ts) and DTO types (types.ts)
├── auth/        AuthContext (JWT session), ProtectedRoute, role→home mapping
├── i18n/        DE/RU translations and the I18nContext / useI18n hook
├── components/  Layout (role-aware nav), LanguageToggle, InvitationNotice
├── lib/         small helpers (error → localized message)
├── pages/
│   ├── LoginPage, ActivatePage
│   ├── admin/     TeachersPage
│   ├── teacher/   StudentsPage, StudentDetailPage, CardCreator, CardRow
│   └── student/   TodayPage, SessionPage, ResultPage, MyCardsPage, SettingsPage
├── App.tsx      routes (role-guarded)
└── main.tsx     providers + bootstrap
```

Teacher/admin screens use a wide desktop layout; student screens are mobile-first
(the layout variant is chosen by role in `Layout.tsx` and styled in `index.css`).
The interface language follows the signed-in user's saved preference.
