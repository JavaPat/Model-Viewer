package com.modelviewer.definitions;

import com.modelviewer.util.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decodes raw object/location definition bytes (OSRS cache index 2, archive 5)
 * into an {@link ObjectDefinition}.
 *
 * The format is opcode-based: read one unsigned byte opcode, handle it, then
 * repeat until opcode 0 (end marker).
 */
public final class ObjectLoader {

    private static final Logger log = LoggerFactory.getLogger(ObjectLoader.class);

    private ObjectLoader() {}

    /**
     * Decodes a single object/location definition.
     *
     * @param id   the object definition ID
     * @param data raw bytes from the cache (readDefinitionData result)
     * @return decoded definition (may be partial if data was malformed), or a
     *         default-valued definition if data is null/empty
     */
    public static ObjectDefinition decode(int id, byte[] data) {
        ObjectDefinition def = new ObjectDefinition(id);
        if (data == null || data.length == 0) {
            return def;
        }
        try {
            Buffer buf = new Buffer(data);
            decodeOpcodes(def, buf);
        } catch (Exception e) {
            log.warn("Object def {} decode error (partial result returned): {}", id, e.getMessage());
        }
        return def;
    }

    private static void decodeOpcodes(ObjectDefinition def, Buffer buf) {
        while (true) {
            int opcode = buf.readUnsignedByte();
            switch (opcode) {
                case 0:
                    // End marker
                    return;

                case 1: {
                    // objectModels + objectTypes: count pairs of (type byte, modelId short)
                    int count = buf.readUnsignedByte();
                    def.objectModels = new int[count];
                    def.objectTypes  = new int[count];
                    for (int i = 0; i < count; i++) {
                        def.objectTypes[i]  = buf.readUnsignedByte();
                        def.objectModels[i] = buf.readUnsignedShort();
                    }
                    break;
                }

                case 2:
                    // name: null-terminated ISO-8859-1 string
                    def.name = buf.readString();
                    break;

                case 5: {
                    // objectModels only (no types): count shorts
                    int count = buf.readUnsignedByte();
                    def.objectModels = new int[count];
                    def.objectTypes  = null;
                    for (int i = 0; i < count; i++) {
                        def.objectModels[i] = buf.readUnsignedShort();
                    }
                    break;
                }

                case 14:
                    // width (discard)
                    buf.readUnsignedByte();
                    break;

                case 15:
                    // length (discard)
                    buf.readUnsignedByte();
                    break;

                case 17:
                case 18:
                    // flags, no data
                    break;

                case 19:
                    // discard 1 byte
                    buf.readUnsignedByte();
                    break;

                case 21:
                case 22:
                case 23:
                    // flags, no data
                    break;

                case 24: {
                    // anim: if value == 0xFFFF it's a sentinel — still consume the short
                    buf.readUnsignedShort();
                    break;
                }

                case 27:
                    // discard 1 byte
                    buf.readUnsignedByte();
                    break;

                case 28:
                    // discard 1 byte
                    buf.readUnsignedByte();
                    break;

                case 29:
                    // ambient (discard)
                    buf.readSignedByte();
                    break;

                case 39:
                    // contrast (discard)
                    buf.readSignedByte();
                    break;

                case 40: {
                    // recolor: count pairs of (find, replace) shorts
                    int count = buf.readUnsignedByte();
                    def.recolorFind    = new short[count];
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

                case 61:
                    // discard 1 short
                    buf.readUnsignedShort();
                    break;

                case 62:
                case 64:
                    // flags, no data
                    break;

                case 65:
                    // scaleX (128 = 1.0×)
                    def.scaleX = buf.readUnsignedShort();
                    break;

                case 66:
                    // scaleY vertical (128 = 1.0×)
                    def.scaleY = buf.readUnsignedShort();
                    break;

                case 67:
                    // scaleZ (128 = 1.0×)
                    def.scaleZ = buf.readUnsignedShort();
                    break;

                case 68:
                    // discard 1 short
                    buf.readUnsignedShort();
                    break;

                case 69:
                    // discard 1 byte
                    buf.readUnsignedByte();
                    break;

                case 70:
                    // discard 1 signed short
                    buf.readSignedShort();
                    break;

                case 71:
                    // discard 1 signed short
                    buf.readSignedShort();
                    break;

                case 72:
                    // discard 1 signed short
                    buf.readSignedShort();
                    break;

                case 73:
                case 74:
                    // flags, no data
                    break;

                case 75:
                    // discard 1 byte
                    buf.readUnsignedByte();
                    break;

                case 77:
                    // 2 shorts (discard)
                    buf.readUnsignedShort();
                    buf.readUnsignedShort();
                    break;

                case 78:
                    // discard 1 short
                    buf.readUnsignedShort();
                    break;

                case 79:
                    // 4 shorts (discard)
                    buf.readUnsignedShort();
                    buf.readUnsignedShort();
                    buf.readUnsignedShort();
                    buf.readUnsignedShort();
                    break;

                case 81:
                    // discard 1 short
                    buf.readUnsignedShort();
                    break;

                case 82:
                    // discard 1 short
                    buf.readUnsignedShort();
                    break;

                case 92:
                    // 3 shorts (discard)
                    buf.readUnsignedShort();
                    buf.readUnsignedShort();
                    buf.readUnsignedShort();
                    break;

                case 93:
                    // flag, no data
                    break;

                case 95:
                    // discard 1 short
                    buf.readUnsignedShort();
                    break;

                case 97:
                case 98:
                case 99:
                    // discard 1 byte each
                    buf.readUnsignedByte();
                    break;

                case 160: {
                    // count shorts (discard)
                    int count = buf.readUnsignedByte();
                    for (int i = 0; i < count; i++) {
                        buf.readUnsignedShort();
                    }
                    break;
                }

                case 162:
                    // short + byte + byte (discard)
                    buf.readUnsignedShort();
                    buf.readUnsignedByte();
                    buf.readUnsignedByte();
                    break;

                case 163:
                case 164:
                case 165:
                case 166:
                case 167:
                case 168:
                    // signed byte each (discard)
                    buf.readSignedByte();
                    break;

                case 169:
                case 170:
                case 171:
                    // flags, no data
                    break;

                case 173:
                    // 2 shorts (discard)
                    buf.readUnsignedShort();
                    buf.readUnsignedShort();
                    break;

                case 177:
                    // flag, no data
                    break;

                case 178:
                    // discard 1 byte
                    buf.readUnsignedByte();
                    break;

                case 186:
                    // discard 1 byte
                    buf.readUnsignedByte();
                    break;

                case 189:
                    // flag, no data
                    break;

                case 190:
                    // discard 1 byte
                    buf.readUnsignedByte();
                    break;

                case 191:
                    // flag, no data
                    break;

                case 196:
                    // discard 1 short
                    buf.readUnsignedShort();
                    break;

                case 197:
                    // discard 1 short
                    buf.readUnsignedShort();
                    break;

                case 200:
                    // discard 1 byte
                    buf.readUnsignedByte();
                    break;

                case 201:
                    // flag, no data
                    break;

                case 202:
                    // discard 1 short
                    buf.readUnsignedShort();
                    break;

                case 249: {
                    // params: count * (uint24 key + bool flag + if flag readString else readInt)
                    int count = buf.readUnsignedByte();
                    for (int i = 0; i < count; i++) {
                        buf.readUint24(); // key (discard)
                        boolean isString = (buf.readUnsignedByte() == 1);
                        if (isString) {
                            buf.readString(); // string value (discard)
                        } else {
                            buf.readInt(); // int value (discard)
                        }
                    }
                    break;
                }

                default:
                    log.debug("Object def {}: unknown opcode {} at offset {} — stopping parse",
                            def.id, opcode, buf.offset - 1);
                    return;
            }
        }
    }
}
