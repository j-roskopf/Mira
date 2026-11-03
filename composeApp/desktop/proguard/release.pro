# Compose enables ProGuard for release builds by default, and these libraries
# are discovered through `META-INF/services` rather than direct references.
# ProGuard cannot see those references and shrinks the providers away, which
# surfaces as a hard crash on first launch (ServiceConfigurationError).
#
# SQLDelight resolves org.sqlite.JDBC through java.sql.DriverManager's
# ServiceLoader, and the packaged app otherwise dies with:
#   No suitable driver found for jdbc:sqlite:...
-keep class org.sqlite.** { *; }
-keep class app.cash.sqldelight.driver.jdbc.** { *; }

# Ktor serialization extensions and HTTP engines are registered via
# java.util.ServiceLoader.
-keep class io.ktor.serialization.kotlinx.** { *; }
-keep class io.ktor.client.engine.cio.** { *; }

# Coil registers its network fetcher through java.util.ServiceLoader.
-keep class coil3.network.ktor3.** { *; }
