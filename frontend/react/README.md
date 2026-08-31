# E-Commerce Frontend

React TypeScript SPA for the e-commerce platform. It talks to the Spring Boot API with Axios and stores a JWT in `localStorage` for authenticated requests.

## Tech Stack

- React 18
- TypeScript 5.3
- Vite 5
- React Router v6
- Axios
- SweetAlert

## Project Structure

```
src/
├── components/
│   ├── auth/           # RouteGuards (RequireAuth, RequireAdmin)
│   ├── layout/         # Navbar, Footer
│   ├── Category/       # CategoryBox
│   └── Product/         # ProductBox
├── pages/
│   ├── Home.tsx
│   ├── Signin.tsx
│   ├── Signup.tsx
│   ├── Category/        # List, add, edit (admin)
│   ├── Product/         # Catalog, details, wishlist, add/edit (admin)
│   ├── cart/
│   ├── Checkout/
│   ├── order/           # History and details
│   └── payment/         # Stripe success / failed
├── utils/
│   ├── auth.ts          # Bearer headers and admin check
│   └── constants.ts     # API_BASE_URL
├── styles/              # CSS variables and mixins
├── assets/
├── types.ts
├── App.tsx
├── main.tsx
└── index.css
```

## Getting Started

Prerequisites: Node.js 18+

```bash
npm install
npm run dev
```

Dev server: `http://localhost:5173`

Copy `.env.example` to `.env.local` if you need to override the API URL:

```env
VITE_API_BASE_URL=http://localhost:8080/
```

## Scripts

| Script | Description |
|--------|-------------|
| `npm run dev` | Start Vite on port 5173 |
| `npm run build` | Typecheck and production build |
| `npm run preview` | Preview the production build |
| `npm run type-check` | TypeScript check only |
| `npm run lint` | ESLint |
| `npm run format` | Prettier format |

## Auth and API

Pages call the backend with Axios. After sign-in, the JWT is stored as `token` and the user as `user` in `localStorage`. Protected routes use `RequireAuth` / `RequireAdmin`. Authenticated requests send `Authorization: Bearer <token>` via `authHeaders()`.

## Styling

- Per-component CSS files
- Shared tokens in `src/styles/variables.css` and `src/styles/mixins.css`
- Bootstrap 4 loaded from `index.html`

## Docker

```bash
# From the repo root
docker compose up frontend
```

The image is built from this directory (`frontend/react/Dockerfile`) and served with Nginx on port 80 (mapped to 3000 in Compose).

## License

MIT
