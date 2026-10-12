plugins {
    java
    id("org.springframework.boot") version "3.5.3"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.gkcontas"
version = "1.0.0"
description = "Fault tolerance against an unstable external service, with the decorator order and the cost of each policy measured"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // The annotations are implemented by aspects; without AOP on the classpath
    // every @CircuitBreaker and @Retry is just documentation.
    implementation("org.springframework.boot:spring-boot-starter-aop")

    // Brings the core module, the Spring Boot 3 auto configuration and the
    // aspects. The programmatic registries come from the same artifact, which
    // is what the experiments use to compose the decorators by hand.
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.3.0")
    // Not pulled transitively: without it no resilience4j_* meter is published.
    implementation("io.github.resilience4j:resilience4j-micrometer:2.3.0")

    implementation("io.micrometer:micrometer-registry-prometheus")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter")
    // The standalone jar carries the client DSL used to program the stubs of a
    // WireMock that runs in a container, outside this JVM.
    testImplementation("org.wiremock:wiremock-standalone:3.9.2")
    testImplementation("org.awaitility:awaitility")
}

dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:1.20.4")
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
