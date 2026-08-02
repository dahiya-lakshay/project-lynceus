// transaction-service: the Phase 1 REST entry point for ingesting and retrieving
// transactions. Unlike shared-lib, this module applies the Spring Boot plugin directly —
// it produces a bootable jar (see the Dockerfile) rather than being a library consumed by
// other modules.
plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":shared-lib"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.liquibase:liquibase-core")
    runtimeOnly(libs.postgresql.driver)

    implementation(libs.mapstruct)
    annotationProcessor(libs.mapstruct.processor)
    testAnnotationProcessor(libs.mapstruct.processor)
    // Lombok and MapStruct annotation processors both act on the same sources
    // (entities use Lombok, the mapper interface uses MapStruct); ordering them
    // explicitly avoids the well-known issue where MapStruct can't see
    // Lombok-generated getters/setters/builders if Lombok's processor doesn't run first.
    annotationProcessor(rootProject.libs.lombok)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
}

tasks.named<Jar>("jar") {
    // Only the executable boot jar is needed (see Dockerfile); disable the plain
    // (non-executable) jar Gradle's java plugin produces by default alongside it.
    enabled = false
}
