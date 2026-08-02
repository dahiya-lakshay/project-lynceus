// shared-lib: plain Java library shared by every Spring Boot service
// (transaction-service, dashboard-bff, and later alert-service /
// customer-service). Deliberately does NOT apply the Spring Boot plugin —
// it is never bootable on its own, only ever a compile/runtime dependency
// of a service module. It still needs `io.spring.dependency-management`
// so the Spring library versions here (spring-web) line up with whatever
// Spring Boot BOM version each consuming service uses.
plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}")
    }
}

dependencies {
    api(libs.jakarta.validation.api)
    api(libs.jackson.annotations)
    api("org.springframework:spring-web")
    // GlobalExceptionHandler's org.springframework.validation.* types (FieldError,
    // BindException) and TenantFilter's OncePerRequestFilter supertype chain
    // (EnvironmentAware, etc.) live in spring-context, which spring-web treats as an
    // optional/provided dependency rather than exporting it transitively.
    api("org.springframework:spring-context")

    implementation(libs.slf4j.api)

    // TenantFilter extends OncePerRequestFilter, which compiles against the Servlet API.
    // compileOnly because the actual implementation is provided at runtime by whichever
    // servlet container the consuming Spring Boot service (spring-boot-starter-web) pulls
    // in — shared-lib itself is never deployed as a standalone webapp.
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    testImplementation("jakarta.servlet:jakarta.servlet-api")

    testImplementation(libs.hibernate.validator)
    testImplementation(libs.glassfish.expressly)
}
