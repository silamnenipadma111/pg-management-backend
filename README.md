# PG Management - Backend (Spring Boot)

REST API for the PG (paying guest) management website: owners register PGs with photos/videos,
facilities and floor -> room -> bed inventory; customers find nearby PGs, request a bed, pay rent and
download invoices; customer care can register a PG on an owner's behalf.

Frontend (Angular): https://github.com/padma-silamneni1/pg-management-frontend

## Tech
Java 17, Spring Boot 3.2 (Web, Data JPA, Security, Validation), JWT, H2 (mock data) / Oracle.

## Run locally (mock JSON data, no database needed)
```bash
mvn spring-boot:run          # http://localhost:8080
```
The default `dev` profile uses an in-memory H2 database (Oracle mode) and loads
`src/main/resources/mock-data/{users,pgs,bookings}.json` on startup. H2 console: `/h2-console`
(JDBC URL `jdbc:h2:mem:pgdb`, user `sa`).

Demo accounts (password `Password@123`):

| Role     | Email               |
|----------|---------------------|
| Owner    | owner@pgstay.in, owner2@pgstay.in |
| Customer | customer@pgstay.in, customer2@pgstay.in |
| Support  | support@pgstay.in   |

## Run with Oracle
```bash
sqlplus pg_management/<pwd>@//host:1521/FREEPDB1 @db/oracle/01_schema.sql
sqlplus pg_management/<pwd>@//host:1521/FREEPDB1 @db/oracle/02_seed_data.sql   # optional demo data
SPRING_PROFILES_ACTIVE=oracle ORACLE_URL=jdbc:oracle:thin:@//host:1521/FREEPDB1 \
ORACLE_USERNAME=pg_management ORACLE_PASSWORD=<pwd> java -jar target/pg-management-backend.jar
```
Alternatively skip `02_seed_data.sql` and start once with `SEED_MOCK_DATA=true` to load the JSON data.
Hibernate runs with `ddl-auto=validate` against the schema.

| File | Purpose |
|------|---------|
| `db/oracle/00_drop_all.sql` | drop all tables |
| `db/oracle/01_schema.sql` | tables, constraints, indexes |
| `db/oracle/02_seed_data.sql` | demo data (same as the JSON files) |
| `db/oracle/03_api_queries.sql` | reference SQL for the main API operations |

Local Oracle for development: `docker run -d -p 1521:1521 -e ORACLE_PASSWORD=<pwd> -e APP_USER=pg_management -e APP_USER_PASSWORD=<pwd> gvenzl/oracle-free:23-slim-faststart`

## Configuration (environment variables)
| Variable | Default | |
|---|---|---|
| `PORT` | 8080 | |
| `SPRING_PROFILES_ACTIVE` | dev | `oracle` for Oracle |
| `JWT_SECRET` | dev value | **set in production** (32+ chars) |
| `CORS_ALLOWED_ORIGINS` | localhost:4200 | comma separated frontend origins |
| `UPLOAD_DIR` | ./uploads | where uploaded images/videos are stored (served at `/uploads/**`) |
| `SEED_MOCK_DATA` | true (dev) / false (oracle) | load JSON mock data into an empty DB |
| `ORACLE_URL`, `ORACLE_USERNAME`, `ORACLE_PASSWORD` | | Oracle connection |

## Booking / payment flow
1. Customer requests a bed (`POST /api/bookings`) -> booking `REQUESTED`, bed `BLOCKED`, owner notified with customer contact details.
2. Owner approves (`POST /api/owner/bookings/{id}/approve`) -> rent installments generated; or rejects -> bed `AVAILABLE` again.
3. Customer pays an installment (`POST /api/payments/{id}/pay`) -> payment `SUBMITTED`, owner notified.
4. Owner accepts (`POST /api/owner/payments/{id}/confirm`) -> payment `PAID`, invoice number issued; on the first payment booking + bed become `BOOKED`.

Booking types: `DAILY`, `WEEKLY` (one payment), `MONTHLY` (monthly installments), `YEARLY` (one yearly
payment or `paymentPlan=MONTHLY` for 12 monthly installments per year).

## API overview
| Area | Endpoints |
|---|---|
| Auth | `POST /api/auth/signup`, `POST /api/auth/signin` (email, password, role), `GET /api/auth/me` |
| Public | `GET /api/pgs?lat&lng&radiusKm&q&city&sharing&minRent&maxRent&gender&ac&sort` (sort = DISTANCE, PRICE_ASC, PRICE_DESC, AVAILABILITY), `GET /api/pgs/{id}`, `GET /api/meta/options`, `POST /api/support/requests` |
| Customer | `POST /api/bookings`, `GET /api/bookings/my`, `POST /api/bookings/{id}/cancel`, `GET /api/payments/my?scope=upcoming\|history`, `POST /api/payments/{id}/pay`, `GET /api/payments/{id}/invoice` |
| Owner | `GET/POST /api/owner/pgs`, `GET/PUT /api/owner/pgs/{id}`, `PATCH /api/owner/beds/{id}/status`, `GET /api/owner/dashboard`, `GET /api/owner/bookings?pgId&status`, `POST /api/owner/bookings/{id}/approve\|reject`, `GET /api/owner/payments?pgId&status`, `POST /api/owner/payments/{id}/confirm\|reject` |
| Support | `GET /api/support/requests`, `POST /api/support/requests/{id}/resolve`, `POST /api/support/pgs` (register PG for an owner) |
| Common | `POST /api/media/upload` (owner/support), `GET /api/notifications`, `POST /api/notifications/{id}/read`, `POST /api/notifications/read-all` |

Send `Authorization: Bearer <token>` for authenticated endpoints.

## Tests
```bash
mvn test
```

## Hosting on GoDaddy
GoDaddy shared/cPanel hosting cannot run Java, so:
- **Angular frontend** -> GoDaddy cPanel (`public_html`), see the frontend README.
- **This API** -> a GoDaddy **VPS** (Ubuntu), behind nginx with HTTPS:

```bash
# on the VPS
sudo apt install -y openjdk-17-jre-headless nginx certbot python3-certbot-nginx
sudo useradd -r -m -d /opt/pg-management pgapp
sudo mkdir -p /opt/pg-management/uploads
# copy target/pg-management-backend.jar and deploy/pg-management.env.example to /opt/pg-management
sudo cp /opt/pg-management/pg-management.env.example /opt/pg-management/pg-management.env   # edit values
sudo chown -R pgapp:pgapp /opt/pg-management
sudo cp deploy/pg-management.service /etc/systemd/system/ && sudo systemctl daemon-reload
sudo systemctl enable --now pg-management
sudo cp deploy/nginx-api.conf /etc/nginx/sites-available/pg-api   # set your domain
sudo ln -s /etc/nginx/sites-available/pg-api /etc/nginx/sites-enabled/ && sudo nginx -t && sudo systemctl reload nginx
sudo certbot --nginx -d api.yourdomain.com
```
In GoDaddy DNS add an `A` record `api` -> VPS IP. Oracle can run on the same VPS (Oracle Free container,
needs ~2 GB RAM) or use any managed Oracle (e.g. Oracle Cloud Autonomous DB) via `ORACLE_URL`.
Alternatively run the API with Docker: `docker build -t pg-api . && docker run -p 8080:8080 --env-file pg-management.env pg-api`.
