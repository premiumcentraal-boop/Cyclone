plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

private const val MOBILERUN_UPSTREAM_URL = "https://github.com/droidrun/mobilerun-portal.git"
private const val MOBILERUN_UPSTREAM_PIN = "d3dae858ecc5ec3bfd3701ff27d58465c9f661b4"

val upstreamCheckout = layout.buildDirectory.dir("upstream/mobilerun-portal")
val upstreamMobilerun = upstreamCheckout.get().asFile.resolve("app/src/main")
val adaptedSources = layout.buildDirectory.dir("generated/mobilerun-src")

fun runGit(vararg args: String): String {
    val process = ProcessBuilder(listOf("git") + args)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exit = process.waitFor()
    require(exit == 0) { "git ${args.joinToString(" ")} failed ($exit): ${output.takeLast(2_000)}" }
    return output.trim()
}

/**
 * Materializes the exact Mobilerun revision Cyclone 3.9 already depended on, but inside this
 * compatibility module's build directory instead of a root-level third_party submodule.
 *
 * The checkout is immutable by contract: every build verifies HEAD against the hard pin before
 * Android compiles it. Deleting build/ removes the dependency completely; no upstream source is
 * mistaken for first-party Cyclone code.
 */
val materializeMobilerunUpstream by tasks.registering {
    doLast {
        val checkout = upstreamCheckout.get().asFile
        val pinFile = checkout.resolve(".cyclone-pin")
        val alreadyPinned = pinFile.isFile &&
            pinFile.readText(Charsets.UTF_8).trim() == MOBILERUN_UPSTREAM_PIN &&
            checkout.resolve("app/src/main/java").isDirectory &&
            checkout.resolve("app/src/main/res").isDirectory
        if (alreadyPinned) return@doLast

        checkout.deleteRecursively()
        checkout.parentFile.mkdirs()
        runGit("init", checkout.absolutePath)
        runGit("-C", checkout.absolutePath, "remote", "add", "origin", MOBILERUN_UPSTREAM_URL)
        runGit("-C", checkout.absolutePath, "fetch", "--depth", "1", "origin", MOBILERUN_UPSTREAM_PIN)
        runGit("-C", checkout.absolutePath, "checkout", "--detach", "FETCH_HEAD")
        val resolved = runGit("-C", checkout.absolutePath, "rev-parse", "HEAD")
        require(resolved == MOBILERUN_UPSTREAM_PIN) {
            "Mobilerun pin mismatch: expected $MOBILERUN_UPSTREAM_PIN, resolved $resolved"
        }
        pinFile.writeText(MOBILERUN_UPSTREAM_PIN + "\n", Charsets.UTF_8)
    }
}

fun wrapGeneratedCallback(
    source: String,
    signature: String,
    nextSignature: String,
    stage: String,
    afterStatement: String? = null,
    disableOnFailure: Boolean = false,
    markEntry: Boolean = true,
): String {
    val methodStart = source.indexOf(signature)
    require(methodStart >= 0) { "Pinned Mobilerun callback not found: $signature" }
    val bodyStart = source.indexOf('{', methodStart) + 1
    val nextStart = source.indexOf(nextSignature, bodyStart)
    require(nextStart > bodyStart) { "Pinned Mobilerun next callback not found: $nextSignature" }
    val methodEnd = source.lastIndexOf("\n    }", nextStart)
    require(methodEnd > bodyStart) { "Pinned Mobilerun callback end not found: $signature" }

    val rawBody = source.substring(bodyStart, methodEnd)
    val statement = afterStatement?.let { rawBody.indexOf(it) } ?: -1
    val prefixEnd = if (statement >= 0) statement + afterStatement!!.length else 0
    val prefix = rawBody.substring(0, prefixEnd)
    val protectedBody = rawBody.substring(prefixEnd)
    val guard = buildString {
        append(prefix)
        if (markEntry) {
            append("\n        com.mobilerun.portal.diagnostics.CycloneProcessDiagnostics.markStage(this, \"")
            append(stage)
            append("\")")
        }
        append("\n        try {")
        append(protectedBody)
        append("\n        } catch (error: Throwable) {\n")
        append("            com.mobilerun.portal.diagnostics.CycloneProcessDiagnostics.recordNonFatal(this, \"")
        append(stage)
        append("\", error)\n")
        if (disableOnFailure) append("            runCatching { disableSelf() }\n")
        append("        }\n")
    }
    return source.substring(0, bodyStart) + guard + source.substring(methodEnd)
}

// Adapt only generated input from the immutable upstream revision:
// - Kotlin 2 makes PackageInfo.versionName nullable.
// - Enhanced Control does not request touch-exploration/two-finger-passthrough modes.
// - Enhanced Control subscribes only to the event classes it consumes, avoiding a second
//   typeAllMask firehose across the entire phone.
// - Enhanced Control ignores Cyclone's own UI events so pairing/status UI cannot feed back into it.
// - Android-owned callbacks are guarded so optional compatibility code cannot crash Cyclone.
val prepareMobilerunSources by tasks.registering(Copy::class) {
    dependsOn(materializeMobilerunUpstream)
    from(upstreamMobilerun.resolve("java"))
    into(adaptedSources)
    filteringCharset = "UTF-8"
    filesMatching("**/MobilerunAccessibilityService.kt") {
        filter { line: String ->
            line
                .replace(
                    "packageManager.getPackageInfo(packageName, 0).versionName",
                    "packageManager.getPackageInfo(packageName, 0).versionName ?: \"unknown\"",
                )
                .replace(
                    "AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE",
                    "0",
                )
                .replace(
                    "flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_2_FINGER_PASSTHROUGH",
                    "flags = flags",
                )
                .replace(
                    "eventTypes = AccessibilityEvent.TYPES_ALL_MASK",
                    "eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_VIEW_SCROLLED",
                )
        }
    }

    doLast {
        val root = adaptedSources.get().asFile

        val applicationFile = root.resolve("com/mobilerun/portal/PortalApplication.kt")
        var applicationSource = applicationFile.readText(Charsets.UTF_8).replace("\r\n", "\n")
        val applicationNeedle = "        super.onCreate()\n"
        require(applicationSource.contains(applicationNeedle)) { "Pinned PortalApplication onCreate changed upstream" }
        applicationSource = applicationSource.replaceFirst(
            applicationNeedle,
            applicationNeedle +
                "        com.mobilerun.portal.diagnostics.CycloneProcessDiagnostics.install(this)\n" +
                "        com.mobilerun.portal.diagnostics.CycloneProcessDiagnostics.markStage(this, \"portal.application.onCreate\")\n",
        )
        applicationFile.writeText(applicationSource, Charsets.UTF_8)

        val serviceFile = root.resolve("com/mobilerun/portal/service/MobilerunAccessibilityService.kt")
        var serviceSource = serviceFile.readText(Charsets.UTF_8).replace("\r\n", "\n")
        val eventSignature = "    override fun onAccessibilityEvent(event: AccessibilityEvent?) {\n"
        require(serviceSource.contains(eventSignature)) { "Pinned Mobilerun accessibility-event callback changed upstream" }
        serviceSource = serviceSource.replaceFirst(
            eventSignature,
            eventSignature +
                "        if (event?.packageName?.toString() == applicationContext.packageName) return\n",
        )
        serviceSource = wrapGeneratedCallback(
            serviceSource,
            "    override fun onCreate()",
            "    override fun onServiceConnected()",
            "enhanced.accessibility.onCreate",
            "\n        super.onCreate()",
            disableOnFailure = true,
        )
        serviceSource = wrapGeneratedCallback(
            serviceSource,
            "    override fun onServiceConnected()",
            "    override fun onAccessibilityEvent(event: AccessibilityEvent?)",
            "enhanced.accessibility.onServiceConnected",
            "\n        super.onServiceConnected()",
            disableOnFailure = true,
        )
        serviceSource = wrapGeneratedCallback(
            serviceSource,
            "    override fun onAccessibilityEvent(event: AccessibilityEvent?)",
            "    override fun onConfigurationChanged(newConfig: Configuration)",
            "enhanced.accessibility.event",
            disableOnFailure = false,
            markEntry = false,
        )
        serviceFile.writeText(serviceSource, Charsets.UTF_8)
    }
}

android {
    namespace = "com.mobilerun.portal"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField(
            "String",
            "UPDATE_FEED_URL",
            "\"https://github.com/droidrun/mobilerun-portal/releases/latest/download/latest.json\""
        )
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            java.srcDir(adaptedSources)
            res.srcDir(upstreamMobilerun.resolve("res"))
            assets.srcDir(upstreamMobilerun.resolve("assets"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.named("preBuild").configure {
    dependsOn(materializeMobilerunUpstream)
    dependsOn(prepareMobilerunSources)
}

tasks.matching { it.name.startsWith("process") && it.name.endsWith("Resources") }.configureEach {
    dependsOn(materializeMobilerunUpstream)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    implementation("io.github.webrtc-sdk:android:137.7151.05")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
