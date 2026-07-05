package com.tracklm.tokitoki.jetbrains

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PathUtilTest {
    @Test
    fun expandsCommonPathForms() {
        val env = mapOf("TOKITOKI_TEST_ROOT" to "/tmp/tokitoki-root")
        assertEquals("/Users/tester/agent", PathUtil.expandPath("~/agent", env + ("HOME" to "/ignored")).replace(System.getProperty("user.home"), "/Users/tester"))
        assertEquals("/tmp/tokitoki-root/bin", PathUtil.expandPath("\$TOKITOKI_TEST_ROOT/bin", env))
        assertEquals("/tmp/tokitoki-root/bin", PathUtil.expandPath("\${TOKITOKI_TEST_ROOT}/bin", env))
        assertEquals("/tmp/tokitoki-root/bin", PathUtil.expandPath("%TOKITOKI_TEST_ROOT%/bin", env))
    }

    @Test
    fun normalizesProviderDirs() {
        assertEquals("claude=/tmp/claude", PathUtil.normalizeProviderDir(" claude = /tmp/claude "))
        assertNull(PathUtil.normalizeProviderDir("missing-separator"))
        assertNull(PathUtil.normalizeProviderDir("codex="))
    }
}
