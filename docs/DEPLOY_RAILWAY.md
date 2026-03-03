# Deploy Polaris on Railway

This guide deploys:
- Spring Boot API as a Railway service
- PostgreSQL via Railway
- Redis via Railway
- React frontend as a Railway service

## 1. Create Railway resources

In your Railway project, add:
- `PostgreSQL`
- `Redis`
- `Service` for backend (from this repo)
- `Service` for frontend (from this repo, `frontend` directory)

## 2. Backend service settings

Use the repository root for backend.

- Build command: `./mvnw clean package -DskipTests`
- Start command: `java -Dserver.port=$PORT -jar target/polaris-0.0.1-SNAPSHOT.jar`

Environment variables (backend):

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://<POSTGRES_HOST>:<POSTGRES_PORT>/<POSTGRES_DB>
SPRING_DATASOURCE_USERNAME=<POSTGRES_USER>
SPRING_DATASOURCE_PASSWORD=<POSTGRES_PASSWORD>

SPRING_DATA_REDIS_HOST=<REDIS_HOST>
SPRING_DATA_REDIS_PORT=<REDIS_PORT>
SPRING_DATA_REDIS_PASSWORD=<REDIS_PASSWORD>

POLARIS_AUTH_ADMIN_USERNAME=admin
POLARIS_AUTH_ADMIN_PASSWORD=Admin@123
POLARIS_AUTH_USER_USERNAME=user
POLARIS_AUTH_USER_PASSWORD=User@123
```

Health check path:

```text
/actuator/health
```

## 3. Frontend service settings

Use `frontend` as service root.

- Build command: `npm ci && npm run build`
- Start command: `npm run preview -- --host 0.0.0.0 --port $PORT`

Environment variables (frontend):

```bash
VITE_API_BASE_URL=https://<your-backend-domain>
```

## 4. CORS configuration

Set backend CORS for your frontend domain:

```bash
POLARIS_CORS_ALLOWED_ORIGINS=https://<your-frontend-domain>
MANAGEMENT_ENDPOINTS_WEB_CORS_ALLOWED_ORIGINS=https://<your-frontend-domain>
```

## 5. Validate

1. Open frontend URL and sign in as `admin/Admin@123`.
2. Open `System Health` and verify DB + Redis are `UP`.
3. Create an API key from `API Keys`.
4. Sign in as `user/User@123`, run simulator requests, verify `200/429/401` behavior.

## 6. Add final URLs to README

After Railway domains are ready, add:
- Frontend URL
- Backend URL
- Backend health URL (`/actuator/health`)
