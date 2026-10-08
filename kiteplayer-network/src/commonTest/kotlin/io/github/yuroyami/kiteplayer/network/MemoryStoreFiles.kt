package io.github.yuroyami.kiteplayer.network

/**
 * The files of a segment store kept in memory, for tests on every target. [failing] makes every
 * operation throw, as a disk that went away does.
 */
internal class MemoryStoreFiles : StoreFiles {
    val files = HashMap<String, ByteArray>()
    private val directories = HashSet<String>()
    private val locks = HashSet<String>()
    var failing = false

    private fun check() {
        if (failing) throw IllegalStateException("the disk is gone")
    }

    override fun lock(path: String): AutoCloseable? {
        check()
        if (!locks.add(path)) return null
        return AutoCloseable { locks -= path }
    }

    override fun makeDirectory(path: String) {
        check()
        var end = path.indexOf('/', 1)
        while (end > 0) {
            directories += path.substring(0, end)
            end = path.indexOf('/', end + 1)
        }
        directories += path
    }

    override fun list(path: String): List<String> {
        check()
        val prefix = "$path/"
        return (files.keys + directories)
            .filter { it.startsWith(prefix) && '/' !in it.substring(prefix.length) }
            .map { it.substring(prefix.length) }
    }

    override fun readAll(path: String): ByteArray? {
        check()
        return files[path]
    }

    override fun read(path: String, position: Long, into: ByteArray, offset: Int, length: Int): Int {
        check()
        val bytes = files[path] ?: throw IllegalStateException("no file $path")
        if (position >= bytes.size) return -1
        val count = minOf(length, bytes.size - position.toInt())
        bytes.copyInto(into, offset, position.toInt(), position.toInt() + count)
        return count
    }

    override fun create(path: String): StoreFileSink {
        check()
        files[path] = ByteArray(0)
        return object : StoreFileSink {
            override fun write(from: ByteArray, offset: Int, length: Int) {
                check()
                files[path] = files.getValue(path) + from.copyOfRange(offset, offset + length)
            }

            override fun close() = check()
        }
    }

    override fun rename(from: String, to: String) {
        check()
        files[to] = files.remove(from) ?: throw IllegalStateException("no file $from")
    }

    override fun delete(path: String) {
        check()
        files -= path
        directories -= path
    }
}
