/*
 * Ported from SPW-Lyrics codec/QqDesLike.kt (QQ Music DES variant)
 */
package com.amflymelyric;

/**
 * QQ Music's DES-compatible construction.
 *
 * <p>24-byte key (three 8-byte sub-keys used in the order schedule[2], schedule[1],
 * schedule[0]), 8-byte ECB blocks, custom S-boxes (some rows deviate from standard DES on
 * purpose), a custom initial/inverse permutation and custom bit permutations.
 *
 * <p>Kotlin {@code UInt} semantics are mapped onto Java {@code int}: unsigned shifts ({@code shr})
 * become {@code >>>}, left shifts ({@code shl}) become {@code <<}.
 */
final class QqDes {

    private QqDes() { }

    // ---------------------------------------------------------------- public API

    public static byte[] decrypt(byte[] input, byte[] key) {
        if (input.length % 8 != 0 || key.length != 24) {
            throw new IllegalArgumentException(
                    "input length must be a multiple of 8 and key length must be 24");
        }
        byte[][][] schedule = new byte[3][16][6];
        keySchedule(copyOfRange(key, 0, 8), schedule[2], true);
        keySchedule(copyOfRange(key, 8, 16), schedule[1], false);
        keySchedule(copyOfRange(key, 16, 24), schedule[0], true);

        byte[] output = new byte[input.length];
        for (int offset = 0; offset < input.length; offset += 8) {
            byte[] block = copyOfRange(input, offset, offset + 8);
            crypt(block, block, schedule[0]);
            crypt(block, block, schedule[1]);
            crypt(block, block, schedule[2]);
            System.arraycopy(block, 0, output, offset, 8);
        }
        return output;
    }

    // ---------------------------------------------------------------- key schedule

    private static void keySchedule(byte[] key, byte[][] schedule, boolean decrypt) {
        int[] keyPermC = {56,48,40,32,24,16,8,0,57,49,41,33,25,17,9,1,58,50,42,34,26,18,10,2,59,51,43,35};
        int[] keyPermD = {62,54,46,38,30,22,14,6,61,53,45,37,29,21,13,5,60,52,44,36,28,20,12,4,27,19,11,3};
        int[] compression = {13,16,10,23,0,4,2,27,14,5,20,9,22,18,11,3,25,7,15,6,26,19,12,1,40,51,30,36,46,54,29,39,50,44,32,47,43,48,38,55,33,52,45,41,49,35,28,31};
        int c = 0;
        int d = 0;
        for (int index = 0; index < 28; index++) {
            c = c | bitNum(key, keyPermC[index], 31 - index);
            d = d | bitNum(key, keyPermD[index], 31 - index);
        }
        for (int round = 0; round < 16; round++) {
            int shift = SHIFTS[round];
            c = ((c << shift) | (c >>> (28 - shift))) & 0xfffffff0;
            d = ((d << shift) | (d >>> (28 - shift))) & 0xfffffff0;
            int target = decrypt ? 15 - round : round;
            for (int index = 0; index < 6; index++) {
                schedule[target][index] = 0;
            }
            for (int index = 0; index < 24; index++) {
                int byteIndex = index / 8;
                schedule[target][byteIndex] = (byte) (schedule[target][byteIndex]
                        | bitNumIntr(c, compression[index], 7 - index % 8));
            }
            for (int index = 24; index < 48; index++) {
                int byteIndex = index / 8;
                schedule[target][byteIndex] = (byte) (schedule[target][byteIndex]
                        | bitNumIntr(d, compression[index] - 27, 7 - index % 8));
            }
        }
    }

    // ---------------------------------------------------------------- block cipher

    private static void crypt(byte[] input, byte[] output, byte[][] key) {
        int[] state = initialPermutation(input);
        for (int round = 0; round < 15; round++) {
            int temporary = state[1];
            state[1] = function(state[1], key[round]) ^ state[0];
            state[0] = temporary;
        }
        state[0] = function(state[1], key[15]) ^ state[0];
        inversePermutation(state, output);
    }

    private static int[] initialPermutation(byte[] input) {
        int[] leftPositions = {57,49,41,33,25,17,9,1,59,51,43,35,27,19,11,3,61,53,45,37,29,21,13,5,63,55,47,39,31,23,15,7};
        int[] rightPositions = {56,48,40,32,24,16,8,0,58,50,42,34,26,18,10,2,60,52,44,36,28,20,12,4,62,54,46,38,30,22,14,6};
        int left = 0;
        int right = 0;
        for (int index = 0; index < 32; index++) {
            left = left | bitNum(input, leftPositions[index], 31 - index);
            right = right | bitNum(input, rightPositions[index], 31 - index);
        }
        return new int[] {left, right};
    }

    private static void inversePermutation(int[] state, byte[] output) {
        int[] byteOrder = {3, 2, 1, 0, 7, 6, 5, 4};
        for (int group = 0; group < 8; group++) {
            int base = 7 - group;
            int value = 0;
            for (int pair = 0; pair < 4; pair++) {
                value = value | bitNumIntr(state[1], base + pair * 8, 7 - pair * 2);
                value = value | bitNumIntr(state[0], base + pair * 8, 6 - pair * 2);
            }
            output[byteOrder[group]] = (byte) value;
        }
    }

    private static int function(int input, byte[] key) {
        int t1 = bitNumIntL(input, 31, 0) | ((input & 0xf0000000) >>> 1) | bitNumIntL(input, 4, 5) |
                bitNumIntL(input, 3, 6) | ((input & 0x0f000000) >>> 3) | bitNumIntL(input, 8, 11) |
                bitNumIntL(input, 7, 12) | ((input & 0x00f00000) >>> 5) | bitNumIntL(input, 12, 17) |
                bitNumIntL(input, 11, 18) | ((input & 0x000f0000) >>> 7) | bitNumIntL(input, 16, 23);
        int t2 = bitNumIntL(input, 15, 0) | ((input & 0x0000f000) << 15) | bitNumIntL(input, 20, 5) |
                bitNumIntL(input, 19, 6) | ((input & 0x00000f00) << 13) | bitNumIntL(input, 24, 11) |
                bitNumIntL(input, 23, 12) | ((input & 0x000000f0) << 11) | bitNumIntL(input, 28, 17) |
                bitNumIntL(input, 27, 18) | ((input & 0x0000000f) << 9) | bitNumIntL(input, 0, 23);
        byte[] large = {
                (byte) (t1 >>> 24), (byte) (t1 >>> 16), (byte) (t1 >>> 8),
                (byte) (t2 >>> 24), (byte) (t2 >>> 16), (byte) (t2 >>> 8),
        };
        for (int index = 0; index < 6; index++) {
            large[index] = (byte) (large[index] ^ key[index]);
        }
        int state = (SBOXES[0][sboxBit((large[0] & 0xff) >>> 2)] << 28) |
                (SBOXES[1][sboxBit(((large[0] & 3) << 4) | ((large[1] & 0xff) >>> 4))] << 24) |
                (SBOXES[2][sboxBit(((large[1] & 15) << 2) | ((large[2] & 0xff) >>> 6))] << 20) |
                (SBOXES[3][sboxBit(large[2] & 63)] << 16) |
                (SBOXES[4][sboxBit((large[3] & 0xff) >>> 2)] << 12) |
                (SBOXES[5][sboxBit(((large[3] & 3) << 4) | ((large[4] & 0xff) >>> 4))] << 8) |
                (SBOXES[6][sboxBit(((large[4] & 15) << 2) | ((large[5] & 0xff) >>> 6))] << 4) |
                SBOXES[7][sboxBit(large[5] & 63)];
        int[] positions = {15,6,19,20,28,11,27,16,0,14,22,25,4,17,30,9,1,7,23,13,31,26,2,8,18,12,29,5,21,10,3,24};
        int permuted = 0;
        for (int index = 0; index < positions.length; index++) {
            permuted = permuted | bitNumIntL(state, positions[index], index);
        }
        return permuted;
    }

    // ---------------------------------------------------------------- bit helpers

    private static int bitNum(byte[] bytes, int bit, int shift) {
        int index = bit / 32 * 4 + 3 - bit % 32 / 8;
        return (((bytes[index] & 0xff) >>> (7 - bit % 8)) & 1) << shift;
    }

    private static int bitNumIntr(int value, int bit, int shift) {
        return ((value >>> (31 - bit)) & 1) << shift;
    }

    private static int bitNumIntL(int value, int bit, int shift) {
        return ((value << bit) & 0x80000000) >>> shift;
    }

    private static int sboxBit(int value) {
        return (value & 0x20) | ((value & 0x1f) >>> 1) | ((value & 1) << 4);
    }

    private static byte[] copyOfRange(byte[] source, int from, int to) {
        byte[] result = new byte[to - from];
        System.arraycopy(source, from, result, 0, to - from);
        return result;
    }

    // ---------------------------------------------------------------- tables

    private static final int[] SHIFTS = {1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1};

    private static final int[][] SBOXES = {
        {14,4,13,1,2,15,11,8,3,10,6,12,5,9,0,7,0,15,7,4,14,2,13,1,10,6,12,11,9,5,3,8,4,1,14,8,13,6,2,11,15,12,9,7,3,10,5,0,15,12,8,2,4,9,1,7,5,11,3,14,10,0,6,13},
        {15,1,8,14,6,11,3,4,9,7,2,13,12,0,5,10,3,13,4,7,15,2,8,15,12,0,1,10,6,9,11,5,0,14,7,11,10,4,13,1,5,8,12,6,9,3,2,15,13,8,10,1,3,15,4,2,11,6,7,12,0,5,14,9},
        {10,0,9,14,6,3,15,5,1,13,12,7,11,4,2,8,13,7,0,9,3,4,6,10,2,8,5,14,12,11,15,1,13,6,4,9,8,15,3,0,11,1,2,12,5,10,14,7,1,10,13,0,6,9,8,7,4,15,14,3,11,5,2,12},
        {7,13,14,3,0,6,9,10,1,2,8,5,11,12,4,15,13,8,11,5,6,15,0,3,4,7,2,12,1,10,14,9,10,6,9,0,12,11,7,13,15,1,3,14,5,2,8,4,3,15,0,6,10,10,13,8,9,4,5,11,12,7,2,14},
        {2,12,4,1,7,10,11,6,8,5,3,15,13,0,14,9,14,11,2,12,4,7,13,1,5,0,15,10,3,9,8,6,4,2,1,11,10,13,7,8,15,9,12,5,6,3,0,14,11,8,12,7,1,14,2,13,6,15,0,9,10,4,5,3},
        {12,1,10,15,9,2,6,8,0,13,3,4,14,7,5,11,10,15,4,2,7,12,9,5,6,1,13,14,0,11,3,8,9,14,15,5,2,8,12,3,7,0,4,10,1,13,11,6,4,3,2,12,9,5,15,10,11,14,1,7,6,0,8,13},
        {4,11,2,14,15,0,8,13,3,12,9,7,5,10,6,1,13,0,11,7,4,9,1,10,14,3,5,12,2,15,8,6,1,4,11,13,12,3,7,14,10,15,6,8,0,5,9,2,6,11,13,8,1,4,10,7,9,5,0,15,14,2,3,12},
        {13,2,8,4,6,15,11,1,10,9,3,14,5,0,12,7,1,15,13,8,10,3,7,4,12,5,6,11,0,14,9,2,7,11,4,1,9,12,14,2,0,6,10,13,15,3,5,8,2,1,14,7,4,10,8,13,15,12,9,0,3,5,6,11},
    };
}
