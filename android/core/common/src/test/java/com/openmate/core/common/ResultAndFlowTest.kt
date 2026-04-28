package com.openmate.core.common

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ResultTest {
    @Test
    fun success_holdsData() {
        val result = Result.Success(42)
        assertThat(result.data).isEqualTo(42)
    }

    @Test
    fun error_holdsException() {
        val ex = RuntimeException("fail")
        val result = Result.Error(ex)
        assertThat(result.exception).isSameInstanceAs(ex)
    }
}

class FlowExtensionsTest {
    @Test
    fun asResult_emitsSuccess() = runTest {
        val flow = flow { emit("hello") }
        val results = flow.asResult().toList()
        assertThat(results).hasSize(1)
        assertThat((results[0] as Result.Success).data).isEqualTo("hello")
    }

    @Test
    fun asResult_emitsErrorOnException() = runTest {
        val flow = flow<String> { throw RuntimeException("boom") }
        val results = flow.asResult().toList()
        assertThat(results).hasSize(1)
        assertThat(results[0]).isInstanceOf(Result.Error::class.java)
    }
}
