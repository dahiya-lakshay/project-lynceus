// dashboard-bff: the Phase 1 backend-for-frontend that aggregates transaction and
// fraud-scoring data into KPIs/charts for the tenant-facing dashboard. Applies the Spring
// Boot plugin directly (produces a bootable jar, like transaction-service) rather than
// being a library.
//
// No spring-boot-starter-data-jpa / Liquibase / postgres-driver here: this service owns no
// primary data of its own (see api-specs/dashboard-bff-api.yaml's description) — everything
// it serves is fetched from transaction-service via TransactionClient and either passed
// through or aggregated in memory, then cached in Redis. No MapStruct either: the
// DashboardService -> BFF-response-DTO transforms involve real logic (zero-filling missing
// risk levels/score buckets, defaulting nulls) rather than a straight entity<->DTO field
// mapping MapStruct is suited for, so plain constructor calls are simpler here than a
// generated mapper would be.
plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":shared-lib"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.named<Jar>("jar") {
    // Only the executable boot jar is needed (see Dockerfile); disable the plain
    // (non-executable) jar Gradle's java plugin produces by default alongside it.
    enabled = false
}
