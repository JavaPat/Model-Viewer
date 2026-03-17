package com.modelviewer.definitions;

import com.modelviewer.util.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decodes raw item definition bytes (OSRS cache index 2, archive 8) into an
 * {@link ItemDefinition}.
 *
 * The format is opcode-based: read one unsigned byte opcode, handle it, then
 * repeat until opcode 0 (end marker).
 */
public final class ItemLoader {

    private static final Logger log = LoggerFactory.getLogger(ItemLoader.class);

    private ItemLoader() {}

    /**
     * Decodes a single item definition.
     *
     * @param id   the item definition ID
     * @param data raw bytes from the cache (readDefinitionData result)
     * @return decoded definition (may be partial if data was malformed), or a
     *         default-valued definition if data is null/empty
     */
    public static ItemDefinition decode(int id, byte[] data) {
        ItemDefinition def = new ItemDefinition(id);
        if (data == null || data.length == 0) {
            return def;
        }
        try {
            Buffer buf = new Buffer(data);
            decodeOpcodes(def, buf);
        } catch (Exception e) {
            log.warn("Item def {} decode error (partial result returned): {}", id, e.getMessage());
        }
        return def;
    }

    private static void decodeOpcodes(ItemDefinition def, Buffer buf) {
        while (true) {
            int opcode = buf.readUnsignedByte();
            switch (opcode) {
                case 0:
                    // End marker
                    return;

                case 1:
                    // inventoryModel
                    def.inventoryModel = buf.readUnsignedShort();
                    break;

                case 2:
                    // name: null-terminated ISO-8859-1 string
                    def.name = buf.readString();
                    break;

                case 4:
                    // zoom2d (discard)
                    buf.readUnsignedShort();
                    break;

                case 5:
                    // xan2d (discard)
                    buf.readUnsignedShort();
                    break;

                case 6:
                    // yan2d (discard)
                    buf.readUnsignedShort();
                    break;

                case 7:
                    // xoff2d (discard)
                    buf.readSignedShort();
                    break;

                case 8:
                    // yoff2d (discard)
                    buf.readSignedShort();
                    break;

                case 11:
                    // stackable flag, no data
                    break;

                case 12:
                    // value (discard)
                    buf.readInt();
                    break;

                case 16:
                    // members flag, no data
                    break;

                case 23:
                    // maleModel0 + signed byte offset
                    def.maleModel0 = buf.readUnsignedShort();
                    buf.readSignedByte(); // offset (discard)
                    break;

                case 24:
                    // maleModel1
                    def.maleModel1 = buf.readUnsignedShort();
                    break;

                case 25:
                    // femaleModel0 + signed byte offset
                    def.femaleModel0 = buf.readUnsignedShort();
                    buf.readSignedByte(); // offset (discard)
                    break;

                case 26:
                    // femaleModel1
                    def.femaleModel1 = buf.readUnsignedShort();
                    break;

                case 30:
                case 31:
                case 32:
                case 33:
                case 34:
                    // ground actions (discard string)
                    buf.readString();
                    break;

                case 35:
                case 36:
                case 37:
                case 38:
                case 39:
                    // inventory actions (discard string)
                    buf.readString();
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

                case 54:
                    // maleModel2
                    def.maleModel2 = buf.readUnsignedShort();
                    break;

                case 55:
                    // femaleModel2
                    def.femaleModel2 = buf.readUnsignedShort();
                    break;

                case 78:
                    // maleHeadModel (discard)
                    buf.readUnsignedShort();
                    break;

                case 79:
                    // femaleHeadModel (discard)
                    buf.readUnsignedShort();
                    break;

                case 90:
                    // discard 1 short
                    buf.readUnsignedShort();
                    break;

                case 91:
                    // discard 1 short
                    buf.readUnsignedShort();
                    break;

                case 93:
                    // zan2d (discard)
                    buf.readUnsignedShort();
                    break;

                case 95:
                    // discard 1 short
                    buf.readUnsignedShort();
                    break;

                case 96:
                    // flag, no data
                    break;

                case 97:
                    // noted id (discard)
                    buf.readUnsignedShort();
                    break;

                case 98:
                    // noted template (discard)
                    buf.readUnsignedShort();
                    break;

                case 100:
                case 101:
                case 102:
                case 103:
                case 104:
                case 105:
                case 106:
                case 107:
                case 108:
                case 109:
                    // stack overrides: short + int each (discard)
                    buf.readUnsignedShort();
                    buf.readInt();
                    break;

                case 110:
                    // resizeX (discard)
                    buf.readUnsignedShort();
                    break;

                case 111:
                    // resizeY (discard)
                    buf.readUnsignedShort();
                    break;

                case 112:
                    // resizeZ (discard)
                    buf.readUnsignedShort();
                    break;

                case 113:
                    // ambient (discard)
                    buf.readSignedByte();
                    break;

                case 114:
                    // contrast (discard)
                    buf.readSignedByte();
                    break;

                case 115:
                    // team (discard)
                    buf.readUnsignedByte();
                    break;

                case 139:
                    // discard 1 short
                    buf.readUnsignedShort();
                    break;

                case 140:
                    // discard 1 short
                    buf.readUnsignedShort();
                    break;

                case 148:
                    // placeholder (discard)
                    buf.readUnsignedShort();
                    break;

                case 149:
                    // placeholder template (discard)
                    buf.readUnsignedShort();
                    break;

                default:
                    log.debug("Item def {}: unknown opcode {} at offset {} — stopping parse",
                            def.id, opcode, buf.offset - 1);
                    return;
            }
        }
    }
}
