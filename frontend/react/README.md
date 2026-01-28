# E-Commerce Frontend

React frontend application for the E-Commerce platform.

## 🛠️ Tech Stack

- React 18
- TypeScript
- Vite
- React Router v6
- Axios
- SweetAlert

## 📁 Project Structure

```
src/
├── api/                    # API client and service layer
│   ├── client.ts          # Axios instance configuration
│   ├── endpoints.ts       # API endpoint definitions
│   └── services/          # API service modules
│       ├── authService.ts
│       ├── productService.ts
│       ├── cartService.ts
│       └── orderService.ts
├── components/            # React components
│   ├── common/           # Reusable UI components
│   │   ├── Button/
│   │   ├── Input/
│   │   └── Modal/
│   ├── layout/           # Layout components
│   │   ├── Navbar/
│   │   └── Footer/
│   └── features/         # Feature-specific components
│       ├── Product/
│       └── Category/
├── pages/                 # Page components
│   ├── Home/
│   ├── Auth/
│   ├── Product/
│   ├── Cart/
│   ├── Checkout/
│   ├── Order/
│   └── Payment/
├── hooks/                 # Custom React hooks
│   ├── useAuth.ts
│   ├── useCart.ts
│   └── useProducts.ts
├── context/               # React Context providers
│   ├── AuthContext.tsx
│   └── CartContext.tsx
├── utils/                 # Utility functions
│   ├── constants.ts
│   ├── formatters.ts
│   └── validators.ts
├── types/                 # TypeScript type definitions
│   └── index.ts
├── styles/                # Global styles
│   ├── variables.css
│   └── mixins.css
├── assets/                # Static assets
├── App.tsx               # Main App component
├── main.tsx              # Entry point
└── index.css             # Global CSS
```

## 🚀 Getting Started

### Prerequisites

- Node.js 18+
- npm or yarn

### Installation

```bash
# Install dependencies
npm install

# Start development server
npm run dev

# Build for production
npm run build

# Preview production build
npm run preview
```

## 🔧 Environment Variables

Create a `.env.local` file in the root directory:

```env
VITE_API_BASE_URL=http://localhost:8080/
```

## 📦 Available Scripts

| Script | Description |
|--------|-------------|
| `npm run dev` | Start development server |
| `npm run build` | Build for production |
| `npm run preview` | Preview production build |
| `npm run lint` | Run ESLint |
| `npm run type-check` | Run TypeScript type checking |

## 🎨 Styling

This project uses:
- CSS Modules for component-specific styles
- CSS Variables for theming (see `src/styles/variables.css`)
- Utility classes (see `src/styles/mixins.css`)
- Bootstrap for base styling

## 🔗 API Integration

The frontend communicates with the backend through the API layer in `src/api/`. The `client.ts` file configures Axios with:
- Base URL configuration
- Request/response interceptors
- Token handling
- Error handling

## 📱 Responsive Design

The application is fully responsive and supports:
- Mobile devices (< 576px)
- Tablets (576px - 992px)
- Desktop (> 992px)

## 🧪 Testing

```bash
# Run tests
npm test

# Run tests with coverage
npm run test:coverage
```

## 📄 License

MIT License
