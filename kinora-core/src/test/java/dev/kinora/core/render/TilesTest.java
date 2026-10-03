package dev.kinora.core.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TilesTest {
    @Test
    void tilesCoverTheFrameAndJoinBack() {
        RenderSettings s = new RenderSettings();
        s.width = 10;
        s.height = 7;
        s.tiles = 3;
        assertEquals(9, Views.count(s));
        int[] size = Views.renderSize(s);
        assertArrayEquals(new int[] {4, 3}, size);
        // A 12 x 9 canvas where each pixel holds its own canvas coordinates.
        byte[][] tiles = new byte[9][size[0] * size[1] * 4];
        for (int t = 0; t < 9; t++) {
            for (int y = 0; y < 3; y++) {
                for (int x = 0; x < 4; x++) {
                    int o = (y * 4 + x) * 4;
                    tiles[t][o] = (byte) ((t % 3) * 4 + x);
                    tiles[t][o + 1] = (byte) ((t / 3) * 3 + y);
                }
            }
        }
        byte[] out = Views.combine(tiles, s);
        assertEquals(10 * 7 * 4, out.length);
        // Cropped centred: canvas offset (1, 1).
        assertEquals(1, out[0]);
        assertEquals(1, out[1]);
        int last = (6 * 10 + 9) * 4;
        assertEquals(10, out[last]);
        assertEquals(7, out[last + 1]);
    }

    @Test
    void tileProjectionShiftsEachPiece() {
        RenderSettings s = new RenderSettings();
        s.tiles = 2;
        assertArrayEquals(new double[] {2, 1, -1}, Views.tile(0, s));
        assertArrayEquals(new double[] {2, -1, -1}, Views.tile(1, s));
        assertArrayEquals(new double[] {2, 1, 1}, Views.tile(2, s));
        s.tiles = 1;
        assertEquals(null, Views.tile(0, s));
    }
}
