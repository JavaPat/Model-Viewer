package com.modelviewer.definitions;

import com.modelviewer.util.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ItemLoader {

    private static final Logger log = LoggerFactory.getLogger(ItemLoader.class);

    private ItemLoader() {}

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
                    return;

                case 1: def.inventoryModel = buf.readUnsignedShort(); break;
                case 2: def.name = buf.readString(); break;
                case 4: buf.readUnsignedShort(); break;
                case 5: buf.readUnsignedShort(); break;
                case 6: buf.readUnsignedShort(); break;
                case 7: buf.readSignedShort(); break;
                case 8: buf.readSignedShort(); break;
                case 11: break;
                case 12: buf.readInt(); break;
                case 16: break;

                case 23:
                    def.maleModel0 = buf.readUnsignedShort();
                    buf.readSignedByte();
                    break;

                case 24: def.maleModel1 = buf.readUnsignedShort(); break;

                case 25:
                    def.femaleModel0 = buf.readUnsignedShort();
                    buf.readSignedByte();
                    break;

                case 26: def.femaleModel1 = buf.readUnsignedShort(); break;

                case 30,31,32,33,34: buf.readString(); break;
                case 35,36,37,38,39: buf.readString(); break;

                case 40: {
                    int count = buf.readUnsignedByte();
                    def.recolorFind = new short[count];
                    def.recolorReplace = new short[count];
                    for (int i = 0; i < count; i++) {
                        def.recolorFind[i] = (short) buf.readUnsignedShort();
                        def.recolorReplace[i] = (short) buf.readUnsignedShort();
                    }
                    break;
                }

                case 41: {
                    int count = buf.readUnsignedByte();
                    def.retextureFind = new short[count];
                    def.retextureReplace = new short[count];
                    for (int i = 0; i < count; i++) {
                        def.retextureFind[i] = (short) buf.readUnsignedShort();
                        def.retextureReplace[i] = (short) buf.readUnsignedShort();
                    }
                    break;
                }

                case 54: def.maleModel2 = buf.readUnsignedShort(); break;
                case 55: def.femaleModel2 = buf.readUnsignedShort(); break;

                case 78,79,90,91,93,95:
                    buf.readUnsignedShort();
                    break;

                case 96: break;

                case 97,98:
                    buf.readUnsignedShort();
                    break;

                case 100,101,102,103,104,105,106,107,108,109:
                    buf.readUnsignedShort();
                    buf.readInt();
                    break;

                case 110,111,112:
                    buf.readUnsignedShort();
                    break;

                case 113,114:
                    buf.readSignedByte();
                    break;

                case 115:
                    buf.readUnsignedByte();
                    break;

                case 139,140,148,149:
                    buf.readUnsignedShort();
                    break;

                // ✅ CRITICAL FIX — params map
                case 249: {
                    int count = buf.readUnsignedByte();
                    for (int i = 0; i < count; i++) {
                        boolean isString = buf.readUnsignedByte() == 1;
                        buf.readUnsignedShort(); // key
                        if (isString) {
                            buf.readString();
                        } else {
                            buf.readInt();
                        }
                    }
                    break;
                }

                default:
                    // ✅ SAFE fallback: skip unknown opcode instead of breaking
                    log.debug("Item {} unknown opcode {}, skipping", def.id, opcode);
                    return;
            }
        }
    }
}