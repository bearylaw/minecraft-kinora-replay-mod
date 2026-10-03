package dev.kinora.core.format;

/**
 * Constants of the {@code .kinora} container. The authoritative description is
 * {@code docs/format.md}; keep the two in step.
 */
public final class KinoraFormat {
    /** File extension, without the dot. */
    public static final String EXTENSION = "kinora";

    /**
     * Eight bytes at offset 0. The high first byte catches 7-bit transfers, CR LF catches newline
     * conversion, 0x1A stops DOS {@code type}, and the final LF catches LF to CR LF conversion.
     */
    public static final byte[] MAGIC = {(byte) 0x89, 'K', 'N', 'R', 0x0D, 0x0A, 0x1A, 0x0A};

    /** Incremented for changes old readers cannot skip over. */
    public static final int FORMAT_MAJOR = 1;
    /** Incremented for additions old readers can ignore (new chunk types, new metadata keys). */
    public static final int FORMAT_MINOR = 0;

    public static final int FILE_HEADER_SIZE = 48;
    public static final int CHUNK_HEADER_SIZE = 40;
    public static final int TRAILER_SIZE = 24;

    /** "KCHK": the first four bytes of every chunk header, used to resynchronise after damage. */
    public static final int CHUNK_SYNC = fourCC("KCHK");
    public static final byte[] TRAILER_MAGIC = {'K', 'N', 'R', 'T', 'R', 'A', 'I', 'L'};

    /** Payload is a zstd frame. */
    public static final int FLAG_ZSTD = 1;
    /** A reader that does not know this chunk type must refuse the file rather than skip it. */
    public static final int FLAG_CRITICAL = 1 << 1;

    /** Upper bound on any single chunk payload, stored or raw: guards against corrupt lengths. */
    public static final int MAX_CHUNK_PAYLOAD = 256 * 1024 * 1024;

    // Chunk types.
    public static final int TYPE_META = fourCC("META");
    public static final int TYPE_MODT = fourCC("MODT");
    public static final int TYPE_PKTS = fourCC("PKTS");
    public static final int TYPE_SNAP = fourCC("SNAP");
    public static final int TYPE_THMB = fourCC("THMB");
    public static final int TYPE_MARK = fourCC("MARK");
    public static final int TYPE_INDX = fourCC("INDX");

    private KinoraFormat() {}

    /** Packs four ASCII characters into an int, first character in the lowest byte. */
    public static int fourCC(String code) {
        if (code.length() != 4) {
            throw new IllegalArgumentException("FourCC must be four characters: " + code);
        }
        int value = 0;
        for (int i = 0; i < 4; i++) {
            char c = code.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                throw new IllegalArgumentException("FourCC must be printable ASCII: " + code);
            }
            value |= c << (8 * i);
        }
        return value;
    }

    public static String fourCCName(int code) {
        char[] chars = new char[4];
        for (int i = 0; i < 4; i++) {
            int c = (code >>> (8 * i)) & 0xFF;
            chars[i] = c >= 0x20 && c <= 0x7E ? (char) c : '?';
        }
        return new String(chars);
    }

    /** Chunk types this version understands. Unknown critical chunks make a file unreadable. */
    public static boolean isKnownType(int type) {
        return type == TYPE_META || type == TYPE_MODT || type == TYPE_PKTS || type == TYPE_SNAP
                || type == TYPE_THMB || type == TYPE_MARK || type == TYPE_INDX;
    }
}
