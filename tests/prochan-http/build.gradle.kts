plugins {
    kotlin("jvm") version "2.4.10"
}

repositories { mavenCentral() }

dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
}

sourceSets {
    main {
        kotlin.srcDir("../../src/ar/mangapro/src/eu/kanade/tachiyomi/extension/ar/mangapro")
        kotlin.include("**/ProChanImageRecoveryInterceptor.kt")
    }
}

tasks.test { useJUnit() }
