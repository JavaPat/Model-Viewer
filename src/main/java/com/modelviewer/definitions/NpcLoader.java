package com.modelviewer.definitions;

import com.modelviewer.util.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decodes raw NPC definition bytes (OSRS cache index 2, archive 7) into an
 * {@link NpcDefinition}.
 *
 * The format is opcode-based: read one unsigned byte opcode, handle it, then
 * repeat until opcode 0 (end marker).
 */
public final class NpcLoader {

    private static final Logger log = LoggerFactory.getLogger(NpcLoader.class);

    private NpcLoader() {}

    /**
     * Decodes a single NPC definition.
     *
     * @param id   the NPC definition ID
     * @param data raw bytes from the cache (readDefinitionData result)
     * @return decoded definition (may be partial if data was malformed), or a
     *         default-valued definition if data is null/empty
     */
    public static NpcDefinition decode(int id, byte[] data) {
        NpcDefinition def = new NpcDefinition(id);
        if (data == null || data.length == 0) {
            return def;
        }
        try {
            Buffer buf = new Buffer(data);
            decodeOpcodes(def, buf);
        } catch (Exception e) {
            log.warn("NPC def {} decode error (partial result returned): {}", id, e.getMessage());
        }
        return def;
    }

    private static void decodeOpcodes(NpcDefinition def, Buffer buf) {
        while (true) {
            int opcode = buf.readUnsignedByte();
            switch (opcode) {
                case 0:
                    // End marker
                    return;

                case 1: {
                    // models: count followed by count model IDs
                    int count = buf.readUnsignedByte();
                    def.models = new int[count];
                    for (int i = 0; i < count; i++) {
                        def.models[i] = buf.readUnsignedShort();
                    }
                    break;
                }

                case 2:
                    // name: null-terminated ISO-8859-1 string
                    def.name = buf.readString();
                    break;

                case 12:
                    // size: tile footprint
                    def.size = buf.readUnsignedByte();
                    break;

                case 13:
                    // standAnim (discard)
                    buf.readUnsignedShort();
                    break;

                case 14:
                    // walkAnim (discard)
                    buf.readUnsignedShort();
                    break;

                case 17:
                    // walkAnim, walkBack, walkLeft, walkRight (4 shorts, discard)
                    buf.readUnsignedShort();
                    buf.readUnsignedShort();
                    buf.readUnsignedShort();
                    buf.readUnsignedShort();
                    break;

                case 18: {
                    // chatheadModels: count followed by count model IDs (discard)
                    int count = buf.readUnsignedByte();
                    for (int i = 0; i < count; i++) {
                        buf.readUnsignedShort();
                    }
                    break;
                }

                case 30:
                case 31:
                case 32:
                case 33:
                case 34:
                    // NPC right-click actions (null-terminated strings, e.g. "Attack", "Talk-to")
                    buf.readString();
                    break;

                case 40: {
                    // recolor: count pairs of (find, replace) shorts
                    int count = buf.readUnsignedByte();
                    def.recolorFind   = new short[count];
                    def.recolorReplace = new short[count];
                    for (int i = 0; i < count; i++) {
                        def.recolorFind[i]    = (short) buf.readUnsignedShort();
                        def.recolorReplace[i] = (short) buf.readUnsignedShort();
                    }
                    break;
                }

                case 41: {
                    // retexture: count pairs of (find, replace) shorts
                    int count = buf.readUnsignedByte();
                    def.retextureFind    = new short[count];
                    def.retextureReplace = new short[count];
                    for (int i = 0; i < count; i++) {
                        def.retextureFind[i]    = (short) buf.readUnsignedShort();
                        def.retextureReplace[i] = (short) buf.readUnsignedShort();
                    }
                    break;
                }

                case 60: {
                    // count shorts (discard)
                    int count = buf.readUnsignedByte();
                    for (int i = 0; i < count; i++) {
                        buf.readUnsignedShort();
                    }
                    break;
                }

                case 93:
                    // flag, no data
                    break;

                case 95:
                    // combatLevel
                    def.combatLevel = buf.readUnsignedShort();
                    break;

                case 97:
                    // scaleXZ (128 = 1.0×)
                    def.scaleXZ = buf.readUnsignedShort();
                    break;

                case 98:
                    // scaleY (128 = 1.0×)
                    def.scaleY = buf.readUnsignedShort();
                    break;

                case 99:
                    // flag, no data
                    break;

                case 100:
                    // ambient (discard)
                    buf.readSignedByte();
                    break;

                case 101:
                    // contrast (discard)
                    buf.readSignedByte();
                    break;

                case 102:
                    // head icon (discard)
                    buf.readUnsignedShort();
                    break;

                case 103:
                    // rotation (discard)
                    buf.readUnsignedShort();
                    break;

                case 106:
                    // varbit + varplayer (discard)
                    buf.readUnsignedShort();
                    buf.readUnsignedShort();
                    break;

                case 107:
                case 109:
                case 111:
                    // flags, no data
                    break;

                case 114:
                    // discard 1 short
                    buf.readUnsignedShort();
                    break;

                case 115:
                    // 5 shorts (discard)
                    buf.readUnsignedShort();
                    buf.readUnsignedShort();
                    buf.readUnsignedShort();
                    buf.readUnsignedShort();
                    buf.readUnsignedShort();
                    break;

                case 116:
                    // discard 1 short
                    buf.readUnsignedShort();
                    break;

                case 118:
                    // 3 shorts (discard)
                    buf.readUnsignedShort();
                    buf.readUnsignedShort();
                    buf.readUnsignedShort();
                    break;

                case 119:
                    // flag, no data
                    break;

                case 123:
                    // discard 1 short
                    buf.readUnsignedShort();
                    break;

                case 124:
                case 125:
                case 126:
                case 127:
                case 128:
                case 129:
                case 130:
                case 131:
                case 134:
                    // flags, no data
                    break;

                case 132:
                    // 3 shorts (discard)
                    buf.readUnsignedShort();
                    buf.readUnsignedShort();
                    buf.readUnsignedShort();
                    break;

                case 200:
                case 201:
                case 204:
                    // flags, no data
                    break;

                default:
                    log.debug("NPC def {}: unknown opcode {} at offset {} — stopping parse",
                            def.id, opcode, buf.offset - 1);
                    return;
            }
        }
    }
}
