package dev.etran.slimetoanvil

import dev.etran.slimetoanvil.convert.ConversionOptions
import dev.etran.slimetoanvil.convert.SlimeConverter
import dev.etran.slimetoanvil.nbt.ByteArrayTag
import dev.etran.slimetoanvil.nbt.CompoundTag
import dev.etran.slimetoanvil.nbt.DoubleTag
import dev.etran.slimetoanvil.nbt.ListTag
import dev.etran.slimetoanvil.nbt.NbtIO
import dev.etran.slimetoanvil.nbt.Tag
import dev.etran.slimetoanvil.slime.Era
import io.airlift.compress.zstd.ZstdCompressor
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.InflaterInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Builds synthetic slime files byte-by-byte from the published format description and checks that the
 * converter turns them into readable Anvil chunks. This validates internal consistency of the reader/writer,
 * not compatibility with every slime fork: please also try real files (see README).
 */
class ConversionTest {

    private fun zstd(raw: ByteArray): ByteArray {
        val c = ZstdCompressor()
        val out = ByteArray(c.maxCompressedLength(raw.size))
        return out.copyOf(c.compress(raw, 0, raw.size, out, 0, out.size))
    }

    private fun DataOutputStream.block(raw: ByteArray) {
        val c = zstd(raw)
        writeInt(c.size); writeInt(raw.size); write(c)
    }

    private fun compound(build: CompoundTag.() -> Unit) = CompoundTag().apply(build)

    private fun readChunk(world: Path, x: Int, z: Int, dir: String = "region"): CompoundTag {
        val bytes = Files.readAllBytes(world.resolve(dir).resolve("r.${x shr 5}.${z shr 5}.mca"))
        val buf = ByteBuffer.wrap(bytes)
        val entry = buf.getInt(((x and 31) + (z and 31) * 32) * 4)
        assertTrue(entry != 0, "chunk $x,$z missing")
        val start = (entry ushr 8) * 4096
        val length = buf.getInt(start)
        assertEquals(2, bytes[start + 4].toInt())
        val data = InflaterInputStream(bytes.copyOfRange(start + 5, start + 4 + length).inputStream()).readBytes()
        return NbtIO.readRoot(data)
    }

    private fun legacyV9(): ByteArray {
        val chunk = ByteArrayOutputStream()
        DataOutputStream(chunk).apply {
            repeat(256) { writeInt(64) }              // height map
            write(ByteArray(256) { 1 })               // biomes (v9, legacy world: no length prefix)
            write(byteArrayOf(0b1, 0))                // section bitmask: section 0
            writeByte(1); write(ByteArray(2048) { 3 })            // block light
            write(ByteArray(4096).also { it[0] = 1 })             // blocks: stone at 0
            write(ByteArray(2048))                                // data
            writeByte(0)                                          // no sky light
        }
        val tile = NbtIO.toBytes(compound {
            this["tiles"] = ListTag().also { it.add(compound { putString("id", "Chest"); putInt("x", 1); putInt("y", 65); putInt("z", 2) }) }
        })
        val entities = NbtIO.toBytes(compound {
            this["entities"] = ListTag().also {
                it.add(compound {
                    putString("id", "Villager")
                    this["Pos"] = ListTag().also { p -> p.add(DoubleTag(3.5)); p.add(DoubleTag(66.0)); p.add(DoubleTag(4.5)) }
                })
            }
        })
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).apply {
            writeShort(0xB10B); writeByte(9); writeByte(1)   // magic, v9, world version 1 (1.8)
            writeShort(0); writeShort(0); writeShort(1); writeShort(1)
            write(byteArrayOf(1))                            // chunk bitmask
            block(chunk.toByteArray())
            block(tile)
            writeByte(1); block(entities)
            block(NbtIO.toBytes(compound { putString("note", "extra") }))
            block(NbtIO.toBytes(compound { this["maps"] = ListTag(Tag.COMPOUND) }))
        }
        return bos.toByteArray()
    }

    @Test
    fun `legacy 1_8 slime v9 converts to anvil`(@org.junit.jupiter.api.io.TempDir tmp: Path) {
        val out = tmp.resolve("lobby")
        val r = SlimeConverter.convert(legacyV9(), out, ConversionOptions(levelName = "lobby"))

        assertEquals(Era.LEGACY, r.era)
        assertEquals(1, r.chunks)
        assertEquals(1, r.tileEntities)
        assertEquals(1, r.entities)
        assertTrue(r.levelDatWritten)
        assertTrue(Files.exists(out.resolve("level.dat")))

        val level = assertNotNull(readChunk(out, 0, 0).getCompound("Level"))
        val section = level.getList("Sections")!!.compounds().single()
        assertEquals(1, (section["Blocks"] as ByteArrayTag).value[0].toInt())
        assertEquals(1, level.getList("TileEntities")!!.size)
        assertEquals(1, level.getList("Entities")!!.size)
        assertEquals(0, level.getInt("LightPopulated"))   // sky light was absent -> server must relight
        assertEquals(256, (level["Biomes"] as ByteArrayTag).value.size)
    }

    @Test
    fun `refuses to overwrite an existing world unless asked`(@org.junit.jupiter.api.io.TempDir tmp: Path) {
        val out = tmp.resolve("w")
        SlimeConverter.convert(legacyV9(), out)
        val failed = runCatching { SlimeConverter.convert(legacyV9(), out) }
        assertTrue(failed.isFailure)
        SlimeConverter.convert(legacyV9(), out, ConversionOptions(overwrite = true))
    }

    private fun modernV11(): ByteArray {
        fun section(stone: Boolean): ByteArray {
            val bs = NbtIO.toBytes(compound {
                this["palette"] = ListTag().also { it.add(compound { putString("Name", if (stone) "minecraft:stone" else "minecraft:air") }) }
            })
            val biomes = NbtIO.toBytes(compound {
                this["palette"] = ListTag().also { it.add(net()) }
            })
            val o = ByteArrayOutputStream()
            DataOutputStream(o).apply {
                writeByte(1); write(ByteArray(2048) { 0x0F })      // sky light
                writeByte(0)                                       // no block light
                writeInt(bs.size); write(bs)
                writeInt(biomes.size); write(biomes)
            }
            return o.toByteArray()
        }

        val chunk = ByteArrayOutputStream()
        DataOutputStream(chunk).apply {
            writeInt(1)                                  // chunk count
            writeInt(2); writeInt(-3)                    // chunk x, z
            writeInt(2)                                  // sections
            write(section(true)); write(section(false))
            val hm = NbtIO.toBytes(compound { })
            writeInt(hm.size); write(hm)
            val tiles = NbtIO.toBytes(compound { this["tileEntities"] = ListTag(Tag.COMPOUND) })
            writeInt(tiles.size); write(tiles)
            val ents = NbtIO.toBytes(compound {
                this["entities"] = ListTag().also { it.add(compound { putString("id", "minecraft:villager") }) }
            })
            writeInt(ents.size); write(ents)
        }
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).apply {
            writeShort(0xB10B); writeByte(11); writeInt(4189)   // v11, a 1.21.x DataVersion
            block(chunk.toByteArray())
            block(NbtIO.toBytes(compound { putString("note", "extra") }))
        }
        return bos.toByteArray()
    }

    private fun net() = compound { putString("Name", "minecraft:plains") }

    @Test
    fun `modern slime v11 converts to 1_18 plus anvil with entity region`(@org.junit.jupiter.api.io.TempDir tmp: Path) {
        val out = tmp.resolve("modern")
        val r = SlimeConverter.convert(modernV11(), out)

        assertEquals(Era.MODERN, r.era)
        assertEquals(4189, r.dataVersion)
        assertEquals(false, r.levelDatWritten)

        val chunk = readChunk(out, 2, -3)
        assertEquals(4189, chunk.getInt("DataVersion"))
        assertEquals(-4, chunk.getInt("yPos"))
        assertEquals(2, chunk.getInt("xPos"))
        assertEquals(-3, chunk.getInt("zPos"))
        val sections = chunk.getList("sections")!!.compounds()
        assertEquals(listOf(-4, -3), sections.map { it.getInt("Y") })
        assertTrue(sections[0].has("SkyLight") && !sections[0].has("BlockLight"))

        val entities = readChunk(out, 2, -3, "entities")
        assertEquals(1, entities.getList("Entities")!!.size)
    }
}
