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

# ProGuard 7.7.0's optimizer rewrites Okio's `Source.buffer()` as a synthetic
# `buffer$...` returning `RealBufferedSource` without updating the method body, so the JVM
# rejects the class with `VerifyError: Bad return type` the first time Coil touches the disk
# cache or a network response. Every image fetch then fails and photos silently disappear in
# release builds. Keeping Okio's members intact stops the optimizer from changing them.
-keep class okio.** { *; }
