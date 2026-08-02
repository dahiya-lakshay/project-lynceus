// Gradle settings for the Lynceus Java multi-module build.
//
// The Foojay resolver lets Gradle auto-provision a JDK 21 toolchain when one
// isn't already installed locally (this dev machine currently only has JDK
// 23) and is also what keeps CI runners working regardless of which JDK
// ships on the image.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "lynceus-services"

include(
    "shared-lib"
    // "transaction-service", // uncommented by Task 6 once its build.gradle.kts exists
    // "dashboard-bff",       // uncommented by Task 8 once its build.gradle.kts exists
    // alert-service and customer-service added in Phase 2
)
