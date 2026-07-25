package com.clauditor.util

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Incremental line reader for Claude's append-only JSONL transcripts.
 *
 * Transcripts routinely reach tens of megabytes, and the plugin re-reads them on timers and
 * on every file-change event. Re-parsing the whole file each time costs time proportional to
 * the *history*, when the new information is proportional to the *append* — so a long-running
 * session gets progressively more expensive to watch. This reader keeps a byte offset and
 * hands back only the lines added since the last pass.
 *
 * Two details make that safe against a file being written concurrently:
 *
 *  - The offset only ever advances to the last newline seen, so a line the writer hasn't
 *    finished is left unconsumed and delivered whole on a later pass.
 *  - A file that shrank was rewritten rather than appended to (a transcript compaction, or a
 *    different session at the same path), so the offset resets and `onRestart` fires *before*
 *    any line is delivered, giving the caller a chance to discard accumulated state.
 *
 * Not thread-safe; callers hold their own lock.
 */
class JsonlTailReader {

    var offset = 0L
        private set

    fun reset() {
        offset = 0
    }

    /**
     * Deliver every complete, non-blank line appended since the last call, and return how
     * many there were. [onRestart] fires first if the file was rewritten.
     */
    fun consume(path: Path, onRestart: () -> Unit = {}, onLine: (String) -> Unit): Int {
        val size = Files.size(path)
        if (size == offset) return 0
        if (size < offset) {
            offset = 0
            onRestart()
        }

        var count = 0
        val leftover = ByteArrayOutputStream()
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            channel.position(offset)
            val buf = ByteBuffer.allocate(CHUNK_BYTES)
            var chunkStart = offset
            while (chunkStart < size) {
                buf.clear()
                val n = channel.read(buf)
                if (n <= 0) break
                buf.flip()
                val bytes = ByteArray(n)
                buf.get(bytes)

                var lineStart = 0
                for (i in 0 until n) {
                    // A newline byte can't occur inside a UTF-8 multi-byte sequence, so
                    // splitting on the raw byte is safe without decoding first.
                    if (bytes[i] != NEWLINE) continue
                    leftover.write(bytes, lineStart, i - lineStart)
                    val line = leftover.toString(Charsets.UTF_8)
                    leftover.reset()
                    lineStart = i + 1
                    offset = chunkStart + lineStart
                    if (line.isNotBlank()) {
                        onLine(line)
                        count++
                    }
                }
                leftover.write(bytes, lineStart, n - lineStart)
                chunkStart += n
            }
        }
        return count
    }

    private companion object {
        const val CHUNK_BYTES = 1 shl 20
        const val NEWLINE = '\n'.code.toByte()
    }
}
