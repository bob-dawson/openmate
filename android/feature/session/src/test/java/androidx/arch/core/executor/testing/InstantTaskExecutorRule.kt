package androidx.arch.core.executor.testing

import org.junit.rules.TestWatcher
import org.junit.runner.Description

class InstantTaskExecutorRule : TestWatcher() {
    override fun starting(description: Description) = Unit
}
