# Cloud-App K8s Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Containerize the Spring Boot/Kotlin cloud-app, deploy it to a DigitalOcean Kubernetes cluster with Postgres + persistent storage, expose it on the internet, and add rolling updates, autoscaling, centralized logs, and metrics — satisfying all 14 lab requirements in `README.md`.

**Architecture:** Multi-stage Dockerfile pushed to DOCR by a GitHub Actions workflow. Kustomize-managed manifests deploy the app and a Postgres StatefulSet (with `do-block-storage` PVC) to DOKS. ingress-nginx fronts the cluster; kube-prometheus-stack + loki-stack provide metrics and logs. App is modified to increment a Postgres-backed visit counter so the persistent storage is meaningfully exercised.

**Tech Stack:** Kotlin 1.9 + Spring Boot 3.2 (Web, Data JPA, Actuator), Micrometer-Prometheus, PostgreSQL 16, Docker (distroless JRE 17 base), Kustomize, DOKS, DOCR, ingress-nginx, kube-prometheus-stack, loki-stack, GitHub Actions.

**Scope note:** This plan produces every code/config artifact and a `DEPLOY.md` runbook. The plan does **not** execute `doctl`/Helm/`kubectl` commands against a real cluster — Adrian runs the runbook himself because (a) it creates paid resources and (b) the DigitalOcean account/PAT is not in this environment.

---

## File Structure

```
cloud-app/
├── README.md                                      [modify – point to DEPLOY.md and spec]
├── build.gradle.kts                               [modify – add JPA, postgres, actuator, micrometer, h2(test)]
├── Dockerfile                                     [create – multi-stage, distroless]
├── .dockerignore                                  [create]
├── DOWN.sh                                        [create – cleanup script]
├── src/main/kotlin/md/utm/cloudapp/
│   ├── CloudAppApplication.kt                     [unchanged]
│   ├── entity/Visit.kt                            [create]
│   ├── repo/VisitRepository.kt                    [create]
│   └── rest/MainController.kt                     [modify – inject repo, increment counter]
├── src/main/resources/
│   ├── application.properties                     [modify – default profile = local]
│   └── application-prod.properties                [create – env-var driven]
├── src/test/kotlin/md/utm/cloudapp/
│   ├── CloudAppApplicationTests.kt                [modify – disable DB autoconfig for context test]
│   ├── repo/VisitRepositoryTest.kt                [create – @DataJpaTest with H2]
│   ├── rest/MainControllerTest.kt                 [create – @WebMvcTest]
│   └── ActuatorEndpointsTest.kt                   [create – integration test, H2]
├── src/test/resources/
│   └── application-test.properties                [create – H2 config for tests]
├── .github/workflows/deploy.yml                   [create – CI/CD]
├── k8s/
│   ├── base/
│   │   ├── kustomization.yaml                     [create]
│   │   ├── namespace.yaml                         [create]
│   │   ├── postgres-secret.example.yaml           [create – placeholder]
│   │   ├── postgres-statefulset.yaml              [create]
│   │   ├── postgres-service.yaml                  [create]
│   │   ├── app-deployment.yaml                    [create]
│   │   ├── app-service.yaml                       [create]
│   │   ├── app-ingress.yaml                       [create]
│   │   ├── app-hpa.yaml                           [create]
│   │   └── app-servicemonitor.yaml                [create]
│   └── overlays/prod/
│       ├── kustomization.yaml                     [create]
│       └── image-patch.yaml                       [create – CI writes here]
└── docs/
    └── DEPLOY.md                                  [create – runbook]
```

Boundaries:
- **App code** is split into `entity/`, `repo/`, `rest/` packages so each file does one thing.
- **K8s manifests** are split per resource (no mega-files) — one concern per YAML, base + overlay separation for environment-specific bits.
- **Runbook** is a single file (`DEPLOY.md`) because it's a sequential script for a human.

---

## Task 1: Add Gradle dependencies and split Spring profiles

**Files:**
- Modify: `build.gradle.kts`
- Modify: `src/main/resources/application.properties`
- Create: `src/main/resources/application-prod.properties`
- Create: `src/test/resources/application-test.properties`
- Modify: `src/test/kotlin/md/utm/cloudapp/CloudAppApplicationTests.kt`

- [ ] **Step 1.1: Update `build.gradle.kts`**

Replace the `dependencies { ... }` block with:

```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("com.h2database:h2")
}
```

- [ ] **Step 1.2: Update `src/main/resources/application.properties`** (local dev defaults — Postgres assumed running on localhost:5432)

```properties
spring.application.name=cloud-app

spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/appdb}
spring.datasource.username=${SPRING_DATASOURCE_USERNAME:appuser}
spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:apppass}

spring.jpa.hibernate.ddl-auto=update
spring.jpa.open-in-view=false

management.endpoints.web.exposure.include=health,info,prometheus
management.endpoint.health.probes.enabled=true
management.health.livenessstate.enabled=true
management.health.readinessstate.enabled=true
```

- [ ] **Step 1.3: Create `src/main/resources/application-prod.properties`**

```properties
# All values come from the Kubernetes Secret / Deployment env.
spring.datasource.url=${SPRING_DATASOURCE_URL}
spring.datasource.username=${SPRING_DATASOURCE_USERNAME}
spring.datasource.password=${SPRING_DATASOURCE_PASSWORD}
spring.jpa.hibernate.ddl-auto=update
```

- [ ] **Step 1.4: Create `src/test/resources/application-test.properties`**

```properties
spring.datasource.url=jdbc:h2:mem:appdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.jpa.hibernate.ddl-auto=create-drop
```

- [ ] **Step 1.5: Make the existing context-load test use the test profile**

Replace `src/test/kotlin/md/utm/cloudapp/CloudAppApplicationTests.kt` with:

```kotlin
package md.utm.cloudapp

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class CloudAppApplicationTests {

    @Test
    fun contextLoads() {
    }
}
```

- [ ] **Step 1.6: Verify build + tests still pass**

Run: `./gradlew clean test`
Expected: BUILD SUCCESSFUL, `CloudAppApplicationTests > contextLoads()` PASSED.

- [ ] **Step 1.7: Commit**

```bash
git add build.gradle.kts src/main/resources src/test/resources src/test/kotlin
git commit -m "feat: add JPA/Postgres/actuator deps and split spring profiles"
```

---

## Task 2: Add `Visit` entity and repository (TDD)

**Files:**
- Create: `src/main/kotlin/md/utm/cloudapp/entity/Visit.kt`
- Create: `src/main/kotlin/md/utm/cloudapp/repo/VisitRepository.kt`
- Create: `src/test/kotlin/md/utm/cloudapp/repo/VisitRepositoryTest.kt`

- [ ] **Step 2.1: Write the failing repo test**

Create `src/test/kotlin/md/utm/cloudapp/repo/VisitRepositoryTest.kt`:

```kotlin
package md.utm.cloudapp.repo

import md.utm.cloudapp.entity.Visit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class VisitRepositoryTest @Autowired constructor(
    private val repo: VisitRepository
) {
    @Test
    fun `saves and counts visits`() {
        assertThat(repo.count()).isZero()
        repo.save(Visit(at = Instant.now()))
        repo.save(Visit(at = Instant.now()))
        assertThat(repo.count()).isEqualTo(2)
    }
}
```

- [ ] **Step 2.2: Run test, verify it fails**

Run: `./gradlew test --tests "md.utm.cloudapp.repo.VisitRepositoryTest"`
Expected: FAIL — `Visit` and `VisitRepository` do not exist (compilation error).

- [ ] **Step 2.3: Create the entity**

Create `src/main/kotlin/md/utm/cloudapp/entity/Visit.kt`:

```kotlin
package md.utm.cloudapp.entity

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import java.time.Instant

@Entity
class Visit(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    val at: Instant
)
```

- [ ] **Step 2.4: Create the repository**

Create `src/main/kotlin/md/utm/cloudapp/repo/VisitRepository.kt`:

```kotlin
package md.utm.cloudapp.repo

import md.utm.cloudapp.entity.Visit
import org.springframework.data.jpa.repository.JpaRepository

interface VisitRepository : JpaRepository<Visit, Long>
```

- [ ] **Step 2.5: Run test, verify PASS**

Run: `./gradlew test --tests "md.utm.cloudapp.repo.VisitRepositoryTest"`
Expected: PASS.

- [ ] **Step 2.6: Commit**

```bash
git add src/main/kotlin/md/utm/cloudapp/entity src/main/kotlin/md/utm/cloudapp/repo src/test/kotlin/md/utm/cloudapp/repo
git commit -m "feat: add Visit entity and JPA repository"
```

---

## Task 3: Update controller to increment counter (TDD)

**Files:**
- Modify: `src/main/kotlin/md/utm/cloudapp/rest/MainController.kt`
- Create: `src/test/kotlin/md/utm/cloudapp/rest/MainControllerTest.kt`

- [ ] **Step 3.1: Write the failing controller test**

Create `src/test/kotlin/md/utm/cloudapp/rest/MainControllerTest.kt`:

```kotlin
package md.utm.cloudapp.rest

import md.utm.cloudapp.entity.Visit
import md.utm.cloudapp.repo.VisitRepository
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(MainController::class)
@ActiveProfiles("test")
class MainControllerTest @Autowired constructor(
    private val mvc: MockMvc
) {
    @MockBean
    lateinit var repo: VisitRepository

    @Test
    fun `returns hello world with current visit count`() {
        whenever(repo.save(any<Visit>())).thenAnswer { it.arguments[0] }
        whenever(repo.count()).thenReturn(42L)

        mvc.perform(get("/"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Hello World")))
            .andExpect(content().string(containsString("42")))
    }
}
```

Note: `mockito-kotlin` is not in the deps. Add it.

- [ ] **Step 3.2: Add `mockito-kotlin` test dep**

In `build.gradle.kts`, inside `dependencies { ... }`, add:

```kotlin
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
```

- [ ] **Step 3.3: Run test, verify it fails**

Run: `./gradlew test --tests "md.utm.cloudapp.rest.MainControllerTest"`
Expected: FAIL — controller still returns `"Hello World!"` with no count.

- [ ] **Step 3.4: Update the controller**

Replace `src/main/kotlin/md/utm/cloudapp/rest/MainController.kt`:

```kotlin
package md.utm.cloudapp.rest

import md.utm.cloudapp.entity.Visit
import md.utm.cloudapp.repo.VisitRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
class MainController(private val visits: VisitRepository) {

    @GetMapping("/")
    fun main(): String {
        visits.save(Visit(at = Instant.now()))
        return "Hello World! Visit #${visits.count()}"
    }
}
```

- [ ] **Step 3.5: Run test, verify PASS**

Run: `./gradlew test --tests "md.utm.cloudapp.rest.MainControllerTest"`
Expected: PASS.

- [ ] **Step 3.6: Run the full test suite to catch regressions**

Run: `./gradlew test`
Expected: all tests PASS.

- [ ] **Step 3.7: Commit**

```bash
git add build.gradle.kts src/main/kotlin/md/utm/cloudapp/rest src/test/kotlin/md/utm/cloudapp/rest
git commit -m "feat: increment Postgres-backed counter on GET /"
```

---

## Task 4: Verify Actuator endpoints respond

**Files:**
- Create: `src/test/kotlin/md/utm/cloudapp/ActuatorEndpointsTest.kt`

- [ ] **Step 4.1: Write the integration test**

Create `src/test/kotlin/md/utm/cloudapp/ActuatorEndpointsTest.kt`:

```kotlin
package md.utm.cloudapp

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActuatorEndpointsTest @Autowired constructor(
    private val mvc: MockMvc
) {
    @Test
    fun `liveness probe returns 200`() {
        mvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk)
    }

    @Test
    fun `readiness probe returns 200`() {
        mvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isOk)
    }

    @Test
    fun `prometheus endpoint exposes JVM metrics`() {
        mvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("jvm_memory_used_bytes")))
    }
}
```

- [ ] **Step 4.2: Run, verify PASS**

Run: `./gradlew test --tests "md.utm.cloudapp.ActuatorEndpointsTest"`
Expected: all 3 tests PASS.

- [ ] **Step 4.3: Commit**

```bash
git add src/test/kotlin/md/utm/cloudapp/ActuatorEndpointsTest.kt
git commit -m "test: verify actuator health and prometheus endpoints"
```

---

## Task 5: Multi-stage Dockerfile and `.dockerignore`

**Files:**
- Create: `Dockerfile`
- Create: `.dockerignore`

- [ ] **Step 5.1: Create `.dockerignore`**

```
.git
.gitignore
.gradle
build
out
.idea
.vscode
*.iml
docs
README.md
LICENSE
DOWN.sh
k8s
.github
```

- [ ] **Step 5.2: Create `Dockerfile`**

```dockerfile
# syntax=docker/dockerfile:1.6

FROM gradle:8.7-jdk17 AS build
WORKDIR /workspace
COPY --chown=gradle:gradle settings.gradle.kts build.gradle.kts gradle.properties* ./
COPY --chown=gradle:gradle gradle ./gradle
COPY --chown=gradle:gradle src ./src
RUN gradle bootJar --no-daemon -x test

FROM gcr.io/distroless/java17-debian12:nonroot
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar /app/app.jar
EXPOSE 8080
USER nonroot
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

- [ ] **Step 5.3: Build the image locally**

Run: `docker build -t cloud-app:dev .`
Expected: build succeeds, last line shows the image SHA.

- [ ] **Step 5.4: Smoke-test with throwaway Postgres**

```bash
docker network create cloud-app-net || true
docker run -d --name pg-smoke --network cloud-app-net \
  -e POSTGRES_USER=appuser -e POSTGRES_PASSWORD=apppass -e POSTGRES_DB=appdb \
  postgres:16-alpine
# wait ~5s for postgres
sleep 5
docker run -d --name app-smoke --network cloud-app-net -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://pg-smoke:5432/appdb \
  -e SPRING_DATASOURCE_USERNAME=appuser \
  -e SPRING_DATASOURCE_PASSWORD=apppass \
  cloud-app:dev
# wait ~15s for spring boot
sleep 15
curl -s localhost:8080
curl -s localhost:8080
curl -s localhost:8080/actuator/health/liveness
```

Expected:
- First curl → `Hello World! Visit #1`
- Second curl → `Hello World! Visit #2`
- Liveness → `{"status":"UP"}`

- [ ] **Step 5.5: Clean up smoke-test containers**

```bash
docker rm -f app-smoke pg-smoke
docker network rm cloud-app-net
```

- [ ] **Step 5.6: Commit**

```bash
git add Dockerfile .dockerignore
git commit -m "feat: add multi-stage distroless Dockerfile"
```

---

## Task 6: K8s base — namespace, Postgres StatefulSet + Service + Secret

**Files:**
- Create: `k8s/base/namespace.yaml`
- Create: `k8s/base/postgres-secret.example.yaml`
- Create: `k8s/base/postgres-statefulset.yaml`
- Create: `k8s/base/postgres-service.yaml`

- [ ] **Step 6.1: Create `k8s/base/namespace.yaml`**

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: cloud-app
  labels:
    name: cloud-app
```

- [ ] **Step 6.2: Create `k8s/base/postgres-secret.example.yaml`**

> **DO NOT** commit a real secret. Adrian applies a real one out-of-band per DEPLOY.md.

```yaml
# Example only — copy, edit, apply manually:
#   kubectl -n cloud-app apply -f my-postgres-secret.yaml
apiVersion: v1
kind: Secret
metadata:
  name: postgres-secret
  namespace: cloud-app
type: Opaque
stringData:
  POSTGRES_USER: appuser
  POSTGRES_PASSWORD: CHANGE_ME
  POSTGRES_DB: appdb
```

- [ ] **Step 6.3: Create `k8s/base/postgres-service.yaml`** (headless service for StatefulSet)

```yaml
apiVersion: v1
kind: Service
metadata:
  name: postgres
  namespace: cloud-app
  labels:
    app: postgres
spec:
  clusterIP: None
  selector:
    app: postgres
  ports:
    - name: pg
      port: 5432
      targetPort: 5432
```

- [ ] **Step 6.4: Create `k8s/base/postgres-statefulset.yaml`**

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: postgres
  namespace: cloud-app
spec:
  serviceName: postgres
  replicas: 1
  selector:
    matchLabels:
      app: postgres
  template:
    metadata:
      labels:
        app: postgres
    spec:
      containers:
        - name: postgres
          image: postgres:16-alpine
          ports:
            - containerPort: 5432
              name: pg
          envFrom:
            - secretRef:
                name: postgres-secret
          volumeMounts:
            - name: data
              mountPath: /var/lib/postgresql/data
              subPath: pgdata
          readinessProbe:
            exec:
              command: ["pg_isready", "-U", "$(POSTGRES_USER)", "-d", "$(POSTGRES_DB)"]
            initialDelaySeconds: 10
            periodSeconds: 5
          livenessProbe:
            exec:
              command: ["pg_isready", "-U", "$(POSTGRES_USER)", "-d", "$(POSTGRES_DB)"]
            initialDelaySeconds: 30
            periodSeconds: 10
          resources:
            requests:
              cpu: 250m
              memory: 256Mi
            limits:
              cpu: "1"
              memory: 1Gi
  volumeClaimTemplates:
    - metadata:
        name: data
      spec:
        accessModes: ["ReadWriteOnce"]
        storageClassName: do-block-storage
        resources:
          requests:
            storage: 10Gi
```

- [ ] **Step 6.5: Validate manifests**

Run:
```bash
kubectl apply --dry-run=client -f k8s/base/namespace.yaml \
  -f k8s/base/postgres-service.yaml \
  -f k8s/base/postgres-statefulset.yaml \
  -f k8s/base/postgres-secret.example.yaml
```
Expected: each resource prints `created (dry run)` with no schema errors.

> If `kubectl` is not installed locally, skip the dry-run and rely on the CI / kustomize check in Task 8.

- [ ] **Step 6.6: Commit**

```bash
git add k8s/base/namespace.yaml k8s/base/postgres-*.yaml
git commit -m "feat(k8s): postgres statefulset with do-block-storage PVC"
```

---

## Task 7: K8s base — app Deployment, Service, Ingress, HPA, ServiceMonitor

**Files:**
- Create: `k8s/base/app-deployment.yaml`
- Create: `k8s/base/app-service.yaml`
- Create: `k8s/base/app-ingress.yaml`
- Create: `k8s/base/app-hpa.yaml`
- Create: `k8s/base/app-servicemonitor.yaml`

- [ ] **Step 7.1: Create `k8s/base/app-deployment.yaml`**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: cloud-app
  namespace: cloud-app
  labels:
    app: cloud-app
spec:
  replicas: 2
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0
      maxSurge: 1
  selector:
    matchLabels:
      app: cloud-app
  template:
    metadata:
      labels:
        app: cloud-app
    spec:
      containers:
        - name: cloud-app
          image: registry.digitalocean.com/REPLACE_ME/cloud-app:latest
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 8080
              name: http
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: prod
            - name: SPRING_DATASOURCE_URL
              value: jdbc:postgresql://postgres.cloud-app.svc.cluster.local:5432/appdb
            - name: SPRING_DATASOURCE_USERNAME
              valueFrom:
                secretKeyRef:
                  name: postgres-secret
                  key: POSTGRES_USER
            - name: SPRING_DATASOURCE_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: postgres-secret
                  key: POSTGRES_PASSWORD
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: http
            initialDelaySeconds: 10
            periodSeconds: 5
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: http
            initialDelaySeconds: 30
            periodSeconds: 10
          resources:
            requests:
              cpu: 200m
              memory: 256Mi
            limits:
              cpu: 500m
              memory: 512Mi
```

- [ ] **Step 7.2: Create `k8s/base/app-service.yaml`**

```yaml
apiVersion: v1
kind: Service
metadata:
  name: cloud-app
  namespace: cloud-app
  labels:
    app: cloud-app
spec:
  type: ClusterIP
  selector:
    app: cloud-app
  ports:
    - name: http
      port: 80
      targetPort: http
```

- [ ] **Step 7.3: Create `k8s/base/app-ingress.yaml`**

Uses `nip.io` so no DNS is needed — host is set by the prod overlay once the LB IP is known. Base uses a wildcard placeholder.

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: cloud-app
  namespace: cloud-app
  annotations:
    nginx.ingress.kubernetes.io/ssl-redirect: "false"
spec:
  ingressClassName: nginx
  rules:
    - host: cloud-app.REPLACE_ME.nip.io
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: cloud-app
                port:
                  number: 80
```

- [ ] **Step 7.4: Create `k8s/base/app-hpa.yaml`**

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: cloud-app
  namespace: cloud-app
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: cloud-app
  minReplicas: 2
  maxReplicas: 5
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80
```

- [ ] **Step 7.5: Create `k8s/base/app-servicemonitor.yaml`**

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: cloud-app
  namespace: cloud-app
  labels:
    release: kube-prometheus-stack
spec:
  selector:
    matchLabels:
      app: cloud-app
  namespaceSelector:
    matchNames:
      - cloud-app
  endpoints:
    - port: http
      path: /actuator/prometheus
      interval: 15s
```

> The `release: kube-prometheus-stack` label matches the default selector of the kube-prometheus-stack Helm chart, so Prometheus picks this ServiceMonitor up automatically.

- [ ] **Step 7.6: Validate (if `kubectl` available)**

Run:
```bash
kubectl apply --dry-run=client -f k8s/base/app-deployment.yaml \
  -f k8s/base/app-service.yaml -f k8s/base/app-ingress.yaml \
  -f k8s/base/app-hpa.yaml
```
Expected: each resource prints `created (dry run)` with no schema errors. (ServiceMonitor will warn that the CRD isn't installed — that's fine, it'll be installed by the Helm chart on the real cluster.)

- [ ] **Step 7.7: Commit**

```bash
git add k8s/base/app-*.yaml
git commit -m "feat(k8s): app deployment, service, ingress, HPA, ServiceMonitor"
```

---

## Task 8: Kustomize base + prod overlay

**Files:**
- Create: `k8s/base/kustomization.yaml`
- Create: `k8s/overlays/prod/kustomization.yaml`
- Create: `k8s/overlays/prod/image-patch.yaml`

- [ ] **Step 8.1: Create `k8s/base/kustomization.yaml`**

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

resources:
  - namespace.yaml
  - postgres-service.yaml
  - postgres-statefulset.yaml
  - app-deployment.yaml
  - app-service.yaml
  - app-ingress.yaml
  - app-hpa.yaml
  - app-servicemonitor.yaml
# Note: postgres-secret.example.yaml is intentionally NOT included.
# Apply the real secret out-of-band (see docs/DEPLOY.md).
```

- [ ] **Step 8.2: Create `k8s/overlays/prod/image-patch.yaml`**

CI rewrites this file's `newTag` field on every deploy.

```yaml
# Patched by .github/workflows/deploy.yml — do not hand-edit `newTag`.
images:
  - name: registry.digitalocean.com/REPLACE_ME/cloud-app
    newName: registry.digitalocean.com/REPLACE_ME/cloud-app
    newTag: latest
```

- [ ] **Step 8.3: Create `k8s/overlays/prod/kustomization.yaml`**

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

namespace: cloud-app

resources:
  - ../../base

# Inherit the image override.
patchesStrategicMerge: []

# Image overrides are merged in from image-patch.yaml via `components`
# at the kustomize CLI level — but the simplest approach is to keep
# them here:
images:
  - name: registry.digitalocean.com/REPLACE_ME/cloud-app
    newTag: latest
```

> **Note for executor:** keep both `image-patch.yaml` and the inline `images:` block — `image-patch.yaml` is what CI edits; the inline block keeps `kustomize build` self-contained. Update both in CI by sed-replacing `newTag: latest` in `kustomization.yaml`.

- [ ] **Step 8.4: Verify `kustomize build` renders cleanly**

Run: `kustomize build k8s/overlays/prod | head -50`
Expected: rendered YAML starts with the Namespace and includes all 8 resources (Namespace, Postgres SVC + STS, App Deployment + SVC + Ingress + HPA, ServiceMonitor). No errors.

> If `kustomize` is not installed: `kubectl kustomize k8s/overlays/prod | head -50` works with kubectl >= 1.21.

- [ ] **Step 8.5: Commit**

```bash
git add k8s/base/kustomization.yaml k8s/overlays
git commit -m "feat(k8s): kustomize base and prod overlay"
```

---

## Task 9: GitHub Actions CI/CD workflow

**Files:**
- Create: `.github/workflows/deploy.yml`

- [ ] **Step 9.1: Create the workflow**

```yaml
name: build-and-deploy

on:
  push:
    branches: [main]
  workflow_dispatch:

env:
  REGISTRY: registry.digitalocean.com
  REGISTRY_NAME: ${{ vars.DOCR_NAME }}        # e.g. "adrian-cloud-app"
  IMAGE_NAME: cloud-app
  CLUSTER_NAME: ${{ vars.DOKS_CLUSTER }}      # e.g. "adrian-doks"
  NAMESPACE: cloud-app

jobs:
  build-and-deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: gradle

      - name: Run tests
        run: ./gradlew test

      - name: Install doctl
        uses: digitalocean/action-doctl@v2
        with:
          token: ${{ secrets.DIGITALOCEAN_ACCESS_TOKEN }}

      - name: Log in to DOCR
        run: doctl registry login --expiry-seconds 1200

      - name: Build and push image
        run: |
          IMAGE="$REGISTRY/$REGISTRY_NAME/$IMAGE_NAME:${{ github.sha }}"
          docker build -t "$IMAGE" -t "$REGISTRY/$REGISTRY_NAME/$IMAGE_NAME:latest" .
          docker push "$IMAGE"
          docker push "$REGISTRY/$REGISTRY_NAME/$IMAGE_NAME:latest"
          echo "IMAGE=$IMAGE" >> "$GITHUB_ENV"

      - name: Save kubeconfig
        run: doctl kubernetes cluster kubeconfig save "$CLUSTER_NAME"

      - name: Roll out new image
        run: |
          kubectl -n "$NAMESPACE" set image deployment/cloud-app cloud-app="$IMAGE"
          kubectl -n "$NAMESPACE" rollout status deployment/cloud-app --timeout=3m
```

- [ ] **Step 9.2: Commit**

```bash
git add .github/workflows/deploy.yml
git commit -m "ci: github actions workflow to build, push to DOCR, and roll out"
```

---

## Task 10: Deploy runbook `docs/DEPLOY.md` and cleanup script

**Files:**
- Create: `docs/DEPLOY.md`
- Create: `DOWN.sh`

- [ ] **Step 10.1: Create `docs/DEPLOY.md`**

```markdown
# Deployment Runbook — cloud-app on DigitalOcean Kubernetes

This is a one-time bootstrap. After it's done, deployments happen automatically via GitHub Actions.

## 0. Prerequisites

- DigitalOcean account with billing enabled (the **$200 / 60-day** new-user credit covers this lab).
- `doctl`, `kubectl`, `helm`, `kustomize`, `docker` installed locally.
- A DigitalOcean Personal Access Token (PAT) with **read + write** scope:
  - https://cloud.digitalocean.com/account/api/tokens
- `doctl auth init --access-token <PAT>`

## 1. Create the DOCR registry (free tier)

```bash
doctl registry create adrian-cloud-app --subscription-tier starter --region fra1
```

`starter` tier = free, 500 MB. Save the registry name (e.g. `adrian-cloud-app`); you'll plug it into manifests + GitHub variables.

## 2. Create the DOKS cluster

```bash
doctl kubernetes cluster create adrian-doks \
  --region fra1 \
  --version latest \
  --size s-2vcpu-2gb \
  --count 2 \
  --auto-upgrade=false \
  --wait
```

Takes ~5 min. Sets your kubectl context to the new cluster.

## 3. Grant DOKS permission to pull from DOCR

```bash
doctl kubernetes cluster registry add adrian-doks
```

This creates an `imagePullSecret` named `registry-adrian-cloud-app` in every namespace and patches the default service account to use it.

## 4. Update placeholders in the repo

Replace the literal string `REPLACE_ME` everywhere:

```bash
grep -rl REPLACE_ME k8s/ | xargs sed -i.bak 's/REPLACE_ME/adrian-cloud-app/g'
```

Then commit the substitution (the CI workflow needs the real registry name too; set GitHub repo Variables — see Step 9).

## 5. Apply the namespace

```bash
kubectl apply -f k8s/base/namespace.yaml
```

## 6. Apply the Postgres secret (REAL credentials, not the example file)

```bash
cat <<EOF | kubectl -n cloud-app apply -f -
apiVersion: v1
kind: Secret
metadata:
  name: postgres-secret
type: Opaque
stringData:
  POSTGRES_USER: appuser
  POSTGRES_PASSWORD: $(openssl rand -base64 24)
  POSTGRES_DB: appdb
EOF
```

> Don't commit this. The example file in `k8s/base/postgres-secret.example.yaml` is intentionally NOT in the kustomization.

## 7. Install ingress-nginx

```bash
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx
helm repo update
helm install ingress-nginx ingress-nginx/ingress-nginx \
  --namespace ingress-nginx --create-namespace \
  --set controller.publishService.enabled=true
```

Wait for the external LB IP:

```bash
kubectl -n ingress-nginx get svc ingress-nginx-controller -w
```

When `EXTERNAL-IP` appears (~2 min), copy it. Then update the ingress host:

```bash
LB_IP=$(kubectl -n ingress-nginx get svc ingress-nginx-controller \
  -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
sed -i.bak "s/cloud-app.REPLACE_ME.nip.io/cloud-app.${LB_IP}.nip.io/" k8s/base/app-ingress.yaml
```

## 8. Install kube-prometheus-stack

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
helm install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  --namespace monitoring --create-namespace \
  --set grafana.adminPassword=admin
```

Expose Grafana via port-forward for the demo:
```bash
kubectl -n monitoring port-forward svc/kube-prometheus-stack-grafana 3000:80
# → http://localhost:3000  user: admin / pass: admin
```

## 9. Install loki-stack

```bash
helm repo add grafana https://grafana.github.io/helm-charts
helm repo update
helm install loki grafana/loki-stack \
  --namespace monitoring \
  --set grafana.enabled=false \
  --set promtail.enabled=true \
  --set loki.persistence.enabled=true \
  --set loki.persistence.storageClassName=do-block-storage \
  --set loki.persistence.size=10Gi
```

In Grafana, add data source: `Loki` → URL `http://loki:3100`.
Explore → `{namespace="cloud-app"}` to tail app logs.

## 10. Configure GitHub Actions secrets and variables

In your repo Settings → Secrets and variables → Actions:

| Type     | Name                          | Value                     |
|----------|-------------------------------|---------------------------|
| Secret   | `DIGITALOCEAN_ACCESS_TOKEN`   | the PAT from step 0       |
| Variable | `DOCR_NAME`                   | `adrian-cloud-app`        |
| Variable | `DOKS_CLUSTER`                | `adrian-doks`             |

## 11. First manual deploy (so the Deployment exists before CI runs `set image`)

```bash
# Build + push locally for the first roll:
doctl registry login
docker build -t registry.digitalocean.com/adrian-cloud-app/cloud-app:bootstrap .
docker push registry.digitalocean.com/adrian-cloud-app/cloud-app:bootstrap

# Patch the image into the deployment manifest and apply the whole bundle:
sed -i.bak 's|:latest|:bootstrap|' k8s/base/app-deployment.yaml
kubectl apply -k k8s/overlays/prod
```

Watch:
```bash
kubectl -n cloud-app get pods -w
```

## 12. Verify everything works

```bash
LB_IP=$(kubectl -n ingress-nginx get svc ingress-nginx-controller \
  -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
curl -H "Host: cloud-app.${LB_IP}.nip.io" http://${LB_IP}/
# → Hello World! Visit #1
curl -H "Host: cloud-app.${LB_IP}.nip.io" http://${LB_IP}/
# → Hello World! Visit #2
```

## 13. Demo checklist (for the instructor)

- `kubectl get nodes,pods,svc,ingress,hpa,pvc -A` — everything green.
- `for i in {1..5}; do curl ...; done` — counter increments.
- `kubectl -n cloud-app delete pod -l app=cloud-app` — counter survives.
- `kubectl -n cloud-app delete pod -l app=postgres` — pod restarts, count survives.
- Push a trivial code change → GitHub Action runs → `kubectl rollout status` shows zero downtime.
- `kubectl -n cloud-app rollout undo deployment/cloud-app` — previous version back.
- `hey -z 60s -c 50 http://${LB_IP}/` (with Host header) → `kubectl -n cloud-app get hpa -w` shows scale-up.
- Grafana dashboards show the traffic spike; Loki Explore shows logs in real time.

## 14. Cleanup (avoid surprise bills!)

```bash
./DOWN.sh
```
```

- [ ] **Step 10.2: Create `DOWN.sh`**

```bash
#!/usr/bin/env bash
set -euo pipefail

CLUSTER="${1:-adrian-doks}"
REGISTRY="${2:-adrian-cloud-app}"

echo ">>> Deleting LoadBalancer first so DO releases the IP:"
helm uninstall ingress-nginx -n ingress-nginx || true

echo ">>> Deleting DOKS cluster $CLUSTER (this also frees PVCs):"
doctl kubernetes cluster delete "$CLUSTER" --force --dangerous

echo ">>> Deleting DOCR registry $REGISTRY:"
doctl registry delete "$REGISTRY" --force

echo ">>> Done. Verify in https://cloud.digitalocean.com that nothing remains."
```

- [ ] **Step 10.3: Make `DOWN.sh` executable**

```bash
chmod +x DOWN.sh
```

- [ ] **Step 10.4: Commit**

```bash
git add docs/DEPLOY.md DOWN.sh
git commit -m "docs: add DOKS deploy runbook and teardown script"
```

---

## Task 11: Refresh `README.md` so it points at the new artifacts

**Files:**
- Modify: `README.md`

- [ ] **Step 11.1: Replace `README.md` contents**

```markdown
# cloud-app

A Spring Boot/Kotlin app deployed to DigitalOcean Kubernetes for the UTM AC cloud lab.

`GET /` returns `Hello World! Visit #N` where N is a counter persisted in Postgres,
proving the database container's PVC really persists data.

## Running locally

Postgres in Docker:
```bash
docker run -d --name pg -p 5432:5432 \
  -e POSTGRES_USER=appuser -e POSTGRES_PASSWORD=apppass -e POSTGRES_DB=appdb \
  postgres:16-alpine
```

The app:
```bash
./gradlew bootRun
curl localhost:8080
```

## Tests

```bash
./gradlew test
```

## Deploying to DigitalOcean

See [`docs/DEPLOY.md`](docs/DEPLOY.md) for the full bootstrap runbook.
See [`docs/superpowers/specs/2026-05-25-cloud-app-k8s-deploy-design.md`](docs/superpowers/specs/2026-05-25-cloud-app-k8s-deploy-design.md) for the design and how each of the 14 lab requirements is satisfied.

## Lab requirements coverage

| # | Requirement | Where |
|---|---|---|
| 1 | Docker image | `Dockerfile` |
| 2 | Published to registry | `.github/workflows/deploy.yml` → DOCR |
| 3 | Deployed to K8s | `k8s/` |
| 4 | K8s on cloud provider | DOKS (per `docs/DEPLOY.md`) |
| 5 | Internet-accessible | ingress-nginx + DO LB |
| 6 | Scaling | `kubectl scale deploy/cloud-app --replicas=N` |
| 7 | Zero-downtime updates | `k8s/base/app-deployment.yaml` rollingUpdate + readiness probe |
| 8 | Rollback | `kubectl rollout undo deployment/cloud-app` |
| 9 | Monitoring | kube-prometheus-stack |
| 10 | Autoscaling | `k8s/base/app-hpa.yaml` |
| 11 | Centralized logs | loki-stack + Promtail |
| 12 | Metrics | micrometer-prometheus + ServiceMonitor |
| 13 | DB in separate container | `k8s/base/postgres-statefulset.yaml` |
| 14 | Storage mounted to DB | `volumeClaimTemplates` (do-block-storage 10Gi) |
```

- [ ] **Step 11.2: Final full test run**

Run: `./gradlew clean test`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 11.3: Commit**

```bash
git add README.md
git commit -m "docs: rewrite README with new architecture and requirement map"
```

---

## Verification checklist (run before declaring the plan done)

- [ ] `./gradlew clean test` passes locally.
- [ ] `docker build -t cloud-app:dev .` succeeds.
- [ ] Smoke test from Task 5.4 produces `Visit #1` and `Visit #2`.
- [ ] `kustomize build k8s/overlays/prod` (or `kubectl kustomize`) renders without errors.
- [ ] `git log --oneline` shows ~11 commits, each scoped to one task.
- [ ] No real secrets in git history (`git log -p | grep -i password` returns nothing committed).

## Notes for the executor

- The plan does NOT create any DigitalOcean resources. Stop after Task 11. Adrian runs `docs/DEPLOY.md` himself.
- If `kubectl` or `kustomize` aren't installed locally, skip the dry-run steps and rely on the `kustomize build` in Task 8.4 (it ships with `kubectl >= 1.21` as `kubectl kustomize`).
- All commits are scoped to one task. Don't batch.
- Honor TDD where it applies (Tasks 2–4). For YAML, "verify by `--dry-run=client` or `kustomize build`" is the equivalent green-bar.
