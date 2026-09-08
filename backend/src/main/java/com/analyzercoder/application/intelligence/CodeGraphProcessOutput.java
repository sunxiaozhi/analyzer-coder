package com.analyzercoder.application.intelligence;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Decodes CLI output without turning the Windows console code page into replacement characters. */
final class CodeGraphProcessOutput {
    private static final Charset WINDOWS_CHINESE = Charset.forName("GB18030");

    private CodeGraphProcessOutput() {}

    static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException ignored) {
            return new String(bytes, WINDOWS_CHINESE);
        }
    }

    static String failureMessage(String output) {
        String normalized = output.toLowerCase(Locale.ROOT);
        if (normalized.contains("不是内部或外部命令")
                || normalized.contains("is not recognized as an internal or external command")
                || normalized.contains("command not found")) {
            return "未找到 CodeGraph CLI，请安装并配置 app.codegraph.executable";
        }
        return "CodeGraph 执行失败: " + output;
    }
}
