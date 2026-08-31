package com.endsustain.internal;

/** 运行时字符串解密器：配合构建期的 ASM 字符串加密使用。 */
public final class Secret {
    private Secret() {}

    public static String decode(String encoded) {
        byte[] data = java.util.Base64.getDecoder().decode(encoded);
        int k = 0x2F;
        for (int i = 0; i < data.length; i++) {
            data[i] ^= (byte) (k = (k * 31 + i) & 0xFF);
        }
        return new String(data, java.nio.charset.StandardCharsets.UTF_8);
    }
}
