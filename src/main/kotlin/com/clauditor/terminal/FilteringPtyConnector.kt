package com.clauditor.terminal

import com.intellij.terminal.pty.PtyProcessTtyConnector
import com.pty4j.PtyProcess
import java.nio.charset.Charset

/**
 * Extends [PtyProcessTtyConnector] to strip terminal escape sequences that
 * JediTerm does not support, preventing noisy WARN log entries.
 *
 * Filters sequences emitted by Claude Code's TUI that JediTerm cannot handle:
 * - Mode 2026 (synchronized output): `ESC[?2026h` / `ESC[?2026l`
 * - Kitty keyboard protocol: `ESC[>Nu` (push) / `ESC[u` (pop)
 * - modifyOtherKeys: `ESC[>N;Nm` (set) / `ESC[>Nm` (reset)
 */
open class FilteringPtyConnector(
    process: PtyProcess,
    charset: Charset
) : PtyProcessTtyConnector(process, charset) {

    companion object {
        private val UNSUPPORTED = Regex(
            "\u001b\\[\\?2026[hl]" +          // synchronized output
            "|\u001b\\[>\\d+(?:;\\d+)*u" +    // kitty push keyboard mode
            "|\u001b\\[u" +                    // kitty pop keyboard mode
            "|\u001b\\[>\\d+(?:;\\d+)*m"       // modifyOtherKeys set/reset
        )
    }

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        val count = super.read(buf, offset, length)
        if (count <= 0) return count

        val raw = String(buf, offset, count)
        val filtered = UNSUPPORTED.replace(raw, "")

        if (filtered.length == count) return count  // nothing stripped

        if (filtered.isEmpty()) return 0

        filtered.toCharArray(buf, offset)
        return filtered.length
    }
}
