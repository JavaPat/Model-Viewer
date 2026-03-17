package com.modelviewer.animation;

import com.modelviewer.util.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decodes a {@link SeqDefinition} from raw bytes read from index 2, archive 9.
 *
 * The format is opcode-based: read one unsigned byte opcode, handle it, then
 * repeat until opcode 0 (end marker).
 */
public final class SeqLoader {

    private static final Logger log = LoggerFactory.getLogger(SeqLoader.class);

    private SeqLoader() {}

    /**
     * Decodes a single sequence definition.
     *
     * @param id   the sequence definition ID (file ID within config archive 9)
     * @param data raw bytes from the cache
     * @return decoded definition (may be partial if data was malformed), or a
     *         default-valued definition if data is null/empty
     */
    public static SeqDefinition decode(int id, byte[] data) {
        SeqDefinition def = new SeqDefinition(id);
        if (data == null || data.length == 0) {
            return def;
        }
        try {
            Buffer buf = new Buffer(data);
            decodeOpcodes(def, buf);
        } catch (Exception e) {
            log.warn("SeqDefinition {} decode error (partial result returned): {}", id, e.getMessage());
        }
        return def;
    }

    private static void decodeOpcodes(SeqDefinition def, Buffer buf) {
        while (true) {
            int opcode = buf.readUnsignedByte();
            switch (opcode) {
                case 0:
                    // End marker
                    return;

                case 1: {
                    // frames: count, then count frameIds (int), then count frameDurations (short)
                    int count = buf.readUnsignedByte();
                    def.frameIds       = new int[count];
                    def.frameDurations = new int[count];
                    for (int i = 0; i < count; i++) {
                        def.frameIds[i] = buf.readInt();
                    }
                    for (int i = 0; i < count; i++) {
                        def.frameDurations[i] = buf.readUnsignedShort();
                    }
                    def.frameCount = count;
                    break;
                }

                case 2:
                    // interleave leave (discard)
                    buf.readUnsignedShort();
                    break;

                case 3:
                    // flag, no data
                    break;

                case 4:
                    // discard 1 byte
                    buf.readUnsignedByte();
                    break;

                case 5:
                    // discard 1 short
                    buf.readUnsignedShort();
                    break;

                case 6:
                    // discard 1 short
                    buf.readUnsignedShort();
                    break;

                case 7:
                    // flag, no data
                    break;

                case 8: {
                    // count entries of (int + short), discard
                    int count = buf.readUnsignedByte();
                    for (int i = 0; i < count; i++) {
                        buf.readInt();
                        buf.readUnsignedShort();
                    }
                    break;
                }

                case 9:
                    // discard 1 byte
                    buf.readUnsignedByte();
                    break;

                case 10: {
                    // count entries of (int + short), discard
                    int count = buf.readUnsignedByte();
                    for (int i = 0; i < count; i++) {
                        buf.readInt();
                        buf.readUnsignedShort();
                    }
                    break;
                }

                case 11: {
                    // loopOffset
                    int val = buf.readUnsignedShort();
                    def.loopOffset = (val == 0xFFFF) ? -1 : val;
                    break;
                }

                case 12:
                    // discard 1 byte
                    buf.readUnsignedByte();
                    break;

                case 13:
                    // discard 1 short
                    buf.readUnsignedShort();
                    break;

                case 14:
                    // discard 1 short
                    buf.readUnsignedShort();
                    break;

                default:
                    // Modern OSRS adds new opcodes regularly; we don't know their
                    // field sizes so we can't skip them safely — stop parsing here.
                    // Demoted to DEBUG so the asset-indexer doesn't flood the log.
                    log.debug("SeqDefinition {}: unknown opcode {} at offset {} — stopping parse",
                            def.id, opcode, buf.offset - 1);
                    return;
            }
        }
    }
}
