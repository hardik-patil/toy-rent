# ToyRental Platform — AWS Elastic Beanstalk Deployment (What & How)

A real, publicly reachable deployment for the "secondary goal: real business launch" in
`CLAUDE.md`. This runs **alongside**, not instead of, the `k8s/`/`helm/` Kubernetes
deployment — that stays the local learning/bottleneck-hunting environment. Nothing here
touches `k8s/`, `helm/`, or any service's business logic beyond one small CORS config
change (see step 4).

Region used throughout: **ap-south-1 (Mumbai)** — matches the business's actual Navi
Mumbai market. Swap it out everywhere below if you'd rather use a different region.

---

## What gets built

```
Browser
   │  HTTP (see the mixed-content caveat in step 5)
   ▼
CloudFront ── origin ──▶  S3 (frontend/dist static build)
   │
   │  frontend calls these directly — same "bypass api-gateway" pattern
   │  STARTUP.md already documents for local dev (Keycloak JWT validation
   │  there was never wired up), carried forward unchanged
   ▼
EB: toy-service-env  ─┐
EB: booking-service-env ─┤  each single-container Docker, own ECR image
EB: api-gateway-env  ─┘  (deployed for architectural parity only — same
                          pre-existing non-functional-gateway gap as local,
                          not fixed by this deployment)
   │
   │  private VPC traffic only, no public exposure
   ▼
EC2 "infra box" — docker-compose.yml (unchanged) running Postgres, Couchbase,
Kafka, Redis, MinIO, Keycloak, WireMock, Prometheus, Grafana, Zipkin
```

| Piece | New file(s) | Purpose |
|---|---|---|
| EB app config | `api-gateway/Dockerrun.aws.json`, `toy-service/Dockerrun.aws.json`, `booking-service/Dockerrun.aws.json` | Tells each EB environment which ECR image to run |
| EB env vars | `<service>/.ebextensions/environment.config` | Reproducible env-var + health-check config per environment, not just console clicks |
| CORS | `toy-service`/`booking-service` `SecurityConfig.java` | `ALLOWED_CORS_ORIGINS` env var (was hardcoded `http://localhost:*`) |
| Frontend build | `frontend/.env.production.example` | Template for the EB URLs the production build points at |

---

## 1. EC2 "infra box" — reuses `docker-compose.yml` unchanged

```bash
# Launch (adjust AMI id for the current Amazon Linux 2023 AMI in ap-south-1):
aws ec2 run-instances \
  --region ap-south-1 \
  --image-id ami-0xxxxxxxxxxxxxxxx \
  --instance-type t3.large \
  --key-name <your-key-pair> \
  --subnet-id <vpc-subnet-id> \
  --security-group-ids <infra-sg-id> \
  --no-associate-public-ip-address \
  --iam-instance-profile Name=<ssm-instance-profile> \
  --block-device-mappings 'DeviceName=/dev/xvda,Ebs={VolumeSize=50,VolumeType=gp3}' \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=toyrental-infra}]'
```

- **Instance size:** starting at `t3.large` (8 GiB). `CLAUDE.md`'s Known Bugs table
  already documents Couchbase's real baseline footprint at ~4.85 GiB on this project —
  if the same OOM pattern shows up here, bump to `t3.xlarge` the same way that was
  resolved for the k8s deployment.
- **No public IP** — only reachable from inside the VPC. Admin access via AWS Systems
  Manager Session Manager (`aws ssm start-session --target <instance-id>`), not an open
  port 22.
- **Security group** (`<infra-sg-id>` above): inbound only from the EB environments'
  security group, on the ports `docker-compose.yml` already publishes — 5432, 8091-8096,
  11210, 9092, 6379, 9000, 8180, 9090, 9091, 3001, 9411. No inbound from the internet.

Once it's up (via SSM session):
```bash
sudo yum install -y docker
sudo systemctl enable --now docker
sudo curl -L "https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
sudo chmod +x /usr/local/bin/docker-compose

# Copy over just what docker-compose.yml needs (from your machine):
#   scp/rsync docker-compose.yml, docker/postgres-init/, wiremock/mappings/,
#   prometheus/, grafana/ to the instance — unchanged, no new compose file.

docker-compose up -d
docker-compose ps   # confirm everything is Up/healthy
```

Note the instance's **private IP** — every `__INFRA_BOX_PRIVATE_IP__` placeholder below
gets replaced with it.

---

## 2. ECR — one repo per backend service

```bash
for svc in api-gateway toy-service booking-service; do
  aws ecr create-repository --region ap-south-1 --repository-name toyrental/$svc
done
```

Names match `helm/values.yaml`'s existing image repository convention.

Build and push each (from the repo root):
```bash
aws ecr get-login-password --region ap-south-1 | \
  docker login --username AWS --password-stdin __ECR_ACCOUNT_ID__.dkr.ecr.ap-south-1.amazonaws.com

for svc in api-gateway toy-service booking-service; do
  (cd $svc && ./mvnw package -DskipTests)
  docker build -t __ECR_ACCOUNT_ID__.dkr.ecr.ap-south-1.amazonaws.com/toyrental/$svc:latest $svc
  docker push __ECR_ACCOUNT_ID__.dkr.ecr.ap-south-1.amazonaws.com/toyrental/$svc:latest
done
```

---

## 3. Three EB environments (Docker platform, single container)

Each service already has:
- `Dockerrun.aws.json` — references its ECR image (`__ECR_ACCOUNT_ID__`/`__AWS_REGION__`
  placeholders to fill in)
- `.ebextensions/environment.config` — env vars + `HealthCheckPath: /actuator/health`

Platform: **"Docker running on 64-bit Amazon Linux 2023"** — verify this is still the
current EB Docker platform branch name when you actually create the environments (AWS
retires old platform branches periodically).

```bash
for svc in api-gateway toy-service booking-service; do
  (cd $svc && \
    eb init $svc --region ap-south-1 --platform docker && \
    eb create $svc-env --single --instance-type t3.small)
done
```

Fill in every placeholder in each service's `.ebextensions/environment.config` before
`eb deploy` (or `eb create`):
- `__INFRA_BOX_PRIVATE_IP__` → the EC2 infra box's private IP from step 1
- `__POSTGRES_TOY_SERVICE_PASSWORD__` / `__POSTGRES_BOOKING_SERVICE_PASSWORD__` /
  `__COUCHBASE_PASSWORD__` / `__MINIO_ACCESS_KEY__` / `__MINIO_SECRET_KEY__` /
  `__ADMIN_PASSWORD__` → same dev-only values already in `docker-compose.yml`/
  `helm/values.yaml`'s `secrets:` block, unless you've changed them for this deployment
- `__TOY_SERVICE_EB_URL__` / `__BOOKING_SERVICE_EB_URL__` → each environment's EB URL,
  only known after `eb create` finishes for that service (create toy-service and
  booking-service first, then fill their URLs into api-gateway's and each other's config,
  then re-deploy)
- `__CLOUDFRONT_DOMAIN__` → filled in after step 5

Security group for all three EB environments: inbound 80 from `0.0.0.0/0` (toy-service
and booking-service are called directly by the browser, same as local dev — see the
diagram above).

---

## 4. CORS: `ALLOWED_CORS_ORIGINS` (already implemented)

`toy-service/src/main/java/com/toyrental/toy/config/SecurityConfig.java` and
`booking-service/src/main/java/com/toyrental/booking/config/SecurityConfig.java` both
used to hardcode `config.setAllowedOriginPatterns(List.of("http://localhost:*"))`. That's
now `@Value("${ALLOWED_CORS_ORIGINS:http://localhost:*}") List<String> allowedOrigins`
injected into the `corsConfigurationSource` bean — defaults to the same local-dev value,
so nothing changes locally. Set via each service's `.ebextensions/environment.config`
(`ALLOWED_CORS_ORIGINS: "https://__CLOUDFRONT_DOMAIN__"`) from step 3.

---

## 5. Frontend: S3 + CloudFront

```bash
cp frontend/.env.production.example frontend/.env.production
# edit frontend/.env.production: VITE_TOY_SERVICE_URL / VITE_BOOKING_SERVICE_URL →
# the two EB environment URLs from step 3 (no code change — frontend/src/lib/api.ts
# already reads these two env vars)

cd frontend && npm run build   # outputs frontend/dist

aws s3 mb s3://toyrental-frontend --region ap-south-1
aws s3 sync dist/ s3://toyrental-frontend --delete
```

Front the bucket with a CloudFront distribution using Origin Access Control (not a public
bucket).

**Known limitation for this pass, not silently worked around:** EB environment URLs are
plain HTTP by default (no ACM cert/custom domain in this minimal-first-pass scope), but
CloudFront serves HTTPS by default — an HTTPS page fetching HTTP APIs is mixed content
and browsers block it. For now, set the CloudFront distribution's viewer protocol policy
to **allow HTTP** (don't force the HTTPS redirect) so both sides match. First thing to
fix once a custom domain + ACM cert exists (see Next steps below).

Once the distribution exists, take its domain name and:
- Fill `__CLOUDFRONT_DOMAIN__` into both services' `ALLOWED_CORS_ORIGINS` (step 3) and
  redeploy (`eb deploy`) so CORS actually allows it.

---

## Verification

1. `docker-compose ps` on the infra box — all services `Up`/healthy.
2. `curl http://<env-url>/actuator/health` → `200` for all three EB environments.
3. Load the CloudFront URL in a browser — catalogue loads real toy data (proves
   frontend → toy-service CORS + connectivity), then run the booking flow end-to-end
   (proves booking-service → toy-service Feign call, and → infra-box Postgres/
   Couchbase/Kafka/WireMock all resolve over the private VPC).
4. Confirm `/admin/login` against the deployed booking-service with the admin
   credentials configured in step 3.

---

## Known limitations / next steps

- **Mixed content workaround** (step 5) — resolve by adding a custom domain + ACM cert
  and switching EB environments to HTTPS listeners, then re-enforce CloudFront's HTTPS
  redirect.
- **No autoscaling beyond EB defaults** — a later pass should mirror the HPA config
  `CLAUDE.md` already documents for k8s (toy-service/booking-service: min 2 / max 8 @
  60% CPU; api-gateway: min 2 / max 10 @ 60% CPU).
- **Single AZ, single EC2 infra box** — no HA for Postgres/Couchbase/Kafka/etc. A later
  pass could migrate the cheap/easy wins to managed services (RDS for Postgres,
  ElastiCache for Redis, S3 directly instead of MinIO) while keeping Kafka/Couchbase/
  Keycloak self-hosted, since there's no direct AWS-managed equivalent for those at this
  scale/cost.
- **api-gateway's JWT validation gap** carries over unchanged from local dev — deploying
  it here doesn't fix that pre-existing issue (see `CLAUDE.md`'s Known Bugs table).
