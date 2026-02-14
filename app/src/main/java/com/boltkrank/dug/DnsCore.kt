package com.boltkrank.dug

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.random.Random

enum class DnsType(val code: Int) {
    A(1),
    NS(2),
    CNAME(5),
    MX(15),
    TXT(16),
    AAAA(28);

    companion object {
        fun fromCode(c: Int): DnsType? = entries.firstOrNull { it.code == c }
    }
}

data class DnsHeader(
    val id: Int,
    val flags: Int,
    val qdCount: Int,
    val anCount: Int,
    val nsCount: Int,
    val arCount: Int
) {
    val qr: Boolean get() = (flags and 0x8000) != 0
    val aa: Boolean get() = (flags and 0x0400) != 0
    val tc: Boolean get() = (flags and 0x0200) != 0
    val rd: Boolean get() = (flags and 0x0100) != 0
    val ra: Boolean get() = (flags and 0x0080) != 0
    val rcode: Int get() = flags and 0x000F
    val opcode: Int get() = (flags shr 11) and 0x000F
}

data class DnsQuestion(val name: String, val type: Int, val qclass: Int)

data class DnsRecord(
    val name: String,
    val type: Int,
    val rclass: Int,
    val ttl: Long,
    val rdata: Any // String for names, ByteArray for unknown, InetAddress for A/AAAA, etc.
)

data class DnsMessage(
    val header: DnsHeader,
    val questions: List<DnsQuestion>,
    val answers: List<DnsRecord>,
    val authority: List<DnsRecord>,
    val additional: List<DnsRecord>
)

class DnsClientUdp(
    private val serverIp: String,
    private val port: Int
) {
    fun query(qname: String, qtype: DnsType, timeoutMs: Int = 2000): ByteArray {
        val id = Random.nextInt(0, 65536)
        val query = DnsCodec.buildQuery(id, qname, qtype.code, recursionDesired = true)

        DatagramSocket().use { sock ->
            sock.soTimeout = timeoutMs
            val addr = InetAddress.getByName(serverIp)
            val req = DatagramPacket(query, query.size, addr, port)
            sock.send(req)

            val buf = ByteArray(4096)
            val resp = DatagramPacket(buf, buf.size)
            sock.receive(resp)
            return resp.data.copyOf(resp.length)
        }
    }
}

object DnsCodec {

    fun buildQuery(id: Int, qname: String, qtype: Int, recursionDesired: Boolean): ByteArray {
        val baos = ByteArrayOutputStream()

        // Header: ID
        writeU16(baos, id)

        // Flags: RD optionally, standard query
        var flags = 0
        if (recursionDesired) flags = flags or 0x0100
        writeU16(baos, flags)

        // QDCOUNT=1, AN/NS/AR=0
        writeU16(baos, 1)
        writeU16(baos, 0)
        writeU16(baos, 0)
        writeU16(baos, 0)

        // Question
        writeQName(baos, qname)
        writeU16(baos, qtype)
        writeU16(baos, 1) // IN

        return baos.toByteArray()
    }

    fun decodeMessage(bytes: ByteArray): DnsMessage {
        val r = ByteReader(bytes)

        val id = r.readU16()
        val flags = r.readU16()
        val qd = r.readU16()
        val an = r.readU16()
        val ns = r.readU16()
        val ar = r.readU16()

        val header = DnsHeader(id, flags, qd, an, ns, ar)

        val questions = buildList {
            repeat(qd) {
                val name = r.readName()
                val type = r.readU16()
                val qclass = r.readU16()
                add(DnsQuestion(name, type, qclass))
            }
        }

        val answers = readRecords(r, an)
        val authority = readRecords(r, ns)
        val additional = readRecords(r, ar)

        return DnsMessage(header, questions, answers, authority, additional)
    }

    private fun readRecords(r: ByteReader, count: Int): List<DnsRecord> {
        return buildList {
            repeat(count) {
                val name = r.readName()
                val type = r.readU16()
                val rclass = r.readU16()
                val ttl = r.readU32()
                val rdlen = r.readU16()
                val rdataStart = r.pos

                val parsed: Any = when (type) {
                    1 -> { // A
                        val b = r.readBytes(4)
                        InetAddress.getByAddress(b)
                    }
                    28 -> { // AAAA
                        val b = r.readBytes(16)
                        InetAddress.getByAddress(b)
                    }
                    2, 5 -> { // NS, CNAME
                        r.readName()
                    }
                    15 -> { // MX
                        val pref = r.readU16()
                        val host = r.readName()
                        "$pref $host"
                    }
                    16 -> { // TXT
                        // one or more strings, each prefixed with length
                        val end = rdataStart + rdlen
                        val parts = mutableListOf<String>()
                        while (r.pos < end) {
                            val l = r.readU8()
                            val s = String(r.readBytes(l), Charsets.UTF_8)
                            parts.add(s)
                        }
                        parts.joinToString(" ")
                    }
                    else -> {
                        // unknown: just store bytes
                        r.readBytes(rdlen)
                    }
                }

                // Ensure we advance exactly rdlen (for safety in case parser under-reads)
                val consumed = r.pos - rdataStart
                if (consumed < rdlen) r.skip(rdlen - consumed)
                if (consumed > rdlen) throw IllegalStateException("RDATA overrun: type=$type consumed=$consumed rdlen=$rdlen")

                add(DnsRecord(name, type, rclass, ttl, parsed))
            }
        }
    }

    private fun writeQName(out: ByteArrayOutputStream, name: String) {
        val labels = name.trimEnd('.').split('.').filter { it.isNotBlank() }
        for (lab in labels) {
            val bytes = lab.toByteArray(Charsets.UTF_8)
            require(bytes.size <= 63) { "Label too long: $lab" }
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0) // terminator
    }

    private fun writeU16(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, v: Long) {
        out.write(((v shr 24) and 0xFF).toInt())
        out.write(((v shr 16) and 0xFF).toInt())
        out.write(((v shr 8) and 0xFF).toInt())
        out.write((v and 0xFF).toInt())
    }
}

class ByteReader(private val data: ByteArray) {
    var pos: Int = 0
        private set

    fun readU8(): Int = data[pos++].toInt() and 0xFF

    fun readU16(): Int {
        val a = readU8()
        val b = readU8()
        return (a shl 8) or b
    }

    fun readU32(): Long {
        val a = readU8().toLong()
        val b = readU8().toLong()
        val c = readU8().toLong()
        val d = readU8().toLong()
        return (a shl 24) or (b shl 16) or (c shl 8) or d
    }

    fun readBytes(n: Int): ByteArray {
        val out = data.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    fun skip(n: Int) {
        pos += n
    }

    /**
     * DNS name decoding with compression pointers.
     */
    fun readName(): String {
        val labels = mutableListOf<String>()
        var jumped = false
        var jumpBackPos = -1

        while (true) {
            val len = readU8()
            if (len == 0) break

            val isPointer = (len and 0xC0) == 0xC0
            if (isPointer) {
                val b2 = readU8()
                val offset = ((len and 0x3F) shl 8) or b2

                if (!jumped) {
                    jumpBackPos = pos
                    jumped = true
                }

                pos = offset
                continue
            }

            val labelBytes = readBytes(len)
            labels.add(String(labelBytes, Charsets.UTF_8))
        }

        if (jumped && jumpBackPos >= 0) {
            pos = jumpBackPos
        }

        return labels.joinToString(".")
    }
}

object DugFormatter {
    fun format(m: DnsMessage): String {
        val h = m.header
        val sb = StringBuilder()

        sb.appendLine(";; ->>HEADER<<- id: ${h.id} opcode: ${h.opcode} rcode: ${h.rcode}")
        sb.appendLine(";; flags: ${flagsString(h)}; QUERY: ${h.qdCount}, ANSWER: ${h.anCount}, AUTHORITY: ${h.nsCount}, ADDITIONAL: ${h.arCount}")
        sb.appendLine()

        if (m.questions.isNotEmpty()) {
            sb.appendLine(";; QUESTION SECTION:")
            for (q in m.questions) {
                sb.appendLine(";${q.name}\t\tIN\t${typeName(q.type)}")
            }
            sb.appendLine()
        }

        section(sb, "ANSWER SECTION:", m.answers)
        section(sb, "AUTHORITY SECTION:", m.authority)
        section(sb, "ADDITIONAL SECTION:", m.additional)

        if (h.tc) {
            sb.appendLine()
            sb.appendLine(";; NOTE: TC=1 (truncated). Next step: retry over TCP.")
        }

        return sb.toString().trimEnd()
    }

    private fun section(sb: StringBuilder, title: String, records: List<DnsRecord>) {
        if (records.isEmpty()) return
        sb.appendLine(";; $title")
        for (rr in records) {
            val rdataStr = when (val v = rr.rdata) {
                is java.net.InetAddress -> v.hostAddress
                is ByteArray -> v.joinToString(" ") { "%02x".format(it) }
                else -> v.toString()
            }
            sb.appendLine("${rr.name}\t${rr.ttl}\tIN\t${typeName(rr.type)}\t$rdataStr")
        }
        sb.appendLine()
    }

    private fun flagsString(h: DnsHeader): String {
        val parts = mutableListOf<String>()
        if (h.qr) parts.add("qr") else parts.add("query")
        if (h.aa) parts.add("aa")
        if (h.tc) parts.add("tc")
        if (h.rd) parts.add("rd")
        if (h.ra) parts.add("ra")
        return parts.joinToString(" ")
    }

    private fun typeName(t: Int): String =
        DnsType.fromCode(t)?.name ?: "TYPE$t"
}
