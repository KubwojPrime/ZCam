import org.gradle.api.tasks.testing.Test
import java.time.Duration

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.hilt) apply false
}

subprojects {
    tasks.withType<Test>().configureEach {
        // Prevent indefinite hangs in CI/local runs.
        timeout.set(Duration.ofMinutes(6))
    }
}
