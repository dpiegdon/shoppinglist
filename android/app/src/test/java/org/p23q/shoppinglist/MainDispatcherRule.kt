package org.p23q.shoppinglist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.debug.DebugProbes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Points viewModelScope's Dispatchers.Main at a test dispatcher so ViewModel tests don't need
 * Robolectric. [dispatcher] is public and **every test using this rule must pass it into
 * `runTest(mainDispatcherRule.dispatcher) { }`**, not bare `runTest { }`, so the test body and the
 * rule's Main dispatcher share one TestCoroutineScheduler rather than two racing ones.
 *
 * Uses [StandardTestDispatcher], NOT UnconfinedTestDispatcher (T-66). Unconfined resumes
 * coroutines *eagerly on whatever thread completes them* — combined with real background threads
 * (Room's IO query context, OkHttp's dispatcher) and Robolectric's own sandbox worker threads,
 * that let a coroutine from one test still be touching the single global Dispatchers.Main on a
 * background thread just as the next test's `starting()` reinstalled it, tripping
 * kotlinx-coroutines-test's concurrent-modification guard ("Dispatchers.Main is used concurrently
 * with setting it") — a real, full-suite-only flake, green in isolation every time. Standard
 * confines all Main-dispatched execution to the single test thread, driven explicitly by the
 * scheduler, so nothing runs on a background thread. The trade-off it forces: coroutines launched
 * on Main (viewModelScope, ViewModel init blocks) no longer run eagerly at launch — a test that
 * fires a `viewModelScope.launch`-backed action and then reads `uiState.value` synchronously must
 * first pump the scheduler (`advanceUntilIdle()`), or await a suspending signal (`job.join()`,
 * `uiState.first { … }`) that pumps it implicitly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = StandardTestDispatcher()) : TestWatcher() {
    @Volatile private var running = false

    override fun starting(description: Description) {
        // A hang's whereabouts (T-369). kotlinx-coroutines-debug's probes track every coroutine's
        // suspension point; they patch the stdlib's DebugProbesKt, which lives in the SYSTEM class
        // loader, so under Robolectric the sandbox-loaded test's coroutines are tracked by the
        // system loader's DebugProbesImpl and runTest's own timeout dump (the sandbox copy) comes
        // out empty. So a watchdog dumps from the system loader by reflection, 30s into a test
        // that is still running — the slowest healthy test takes 10s — plus every Java thread's
        // stack, into stderr, which the test report keeps. Installed once per JVM: the patching
        // is a ByteBuddy redefinition, not something to repeat 800 times.
        installProbes()
        Dispatchers.setMain(dispatcher)
        running = true
        val name = description.displayName
        Thread {
            try { Thread.sleep(30_000) } catch (_: InterruptedException) { return@Thread }
            if (!running) return@Thread
            System.err.println("=== watchdog (T-369): $name still running after 30s ===")
            try {
                val probes = ClassLoader.getSystemClassLoader().loadClass("kotlinx.coroutines.debug.DebugProbes")
                val instance = probes.getField("INSTANCE").get(null)
                probes.getMethod("dumpCoroutines", java.io.PrintStream::class.java).invoke(instance, System.err)
            } catch (e: Throwable) {
                System.err.println("(system-loader coroutine dump failed: $e)")
            }
            System.err.println("=== watchdog (T-369): thread dump ===")
            for ((thread, frames) in Thread.getAllStackTraces()) {
                System.err.println("-- ${thread.name} (${thread.state})")
                frames.take(14).forEach { System.err.println("    at $it") }
            }
        }.apply { isDaemon = true; start() }
    }

    override fun finished(description: Description) {
        running = false
        Dispatchers.resetMain()
    }

    private companion object {
        @Volatile private var probesInstalled = false

        @Synchronized
        fun installProbes() {
            if (probesInstalled) return
            DebugProbes.enableCreationStackTraces = false
            DebugProbes.install()
            probesInstalled = true
        }
    }
}
