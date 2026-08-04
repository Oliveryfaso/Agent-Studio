package dev.agentconfig.workbench.scan;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

final class TextHints {
    private TextHints() {}

    static Result inspect(byte[] bytes) {
        if (bytes.length == 0) {
            return new Result(EncodingHint.EMPTY, LineEnding.NONE);
        }
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            return new Result(EncodingHint.UTF8_BOM, lineEnding(bytes, 3));
        }
        if (startsWith(bytes, 0xFF, 0xFE)) {
            return new Result(EncodingHint.UTF16_LE_BOM, LineEnding.UNKNOWN);
        }
        if (startsWith(bytes, 0xFE, 0xFF)) {
            return new Result(EncodingHint.UTF16_BE_BOM, LineEnding.UNKNOWN);
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return new Result(EncodingHint.UTF8, lineEnding(bytes, 0));
        } catch (CharacterCodingException exception) {
            return new Result(EncodingHint.BINARY_OR_UNKNOWN, LineEnding.UNKNOWN);
        }
    }

    private static LineEnding lineEnding(byte[] bytes, int offset) {
        boolean lf = false;
        boolean crlf = false;
        boolean cr = false;
        for (int index = offset; index < bytes.length; index++) {
            if (bytes[index] == '\r') {
                if (index + 1 < bytes.length && bytes[index + 1] == '\n') {
                    crlf = true;
                    index++;
                } else {
                    cr = true;
                }
            } else if (bytes[index] == '\n') {
                lf = true;
            }
        }
        int types = (lf ? 1 : 0) + (crlf ? 1 : 0) + (cr ? 1 : 0);
        if (types == 0) {
            return LineEnding.NONE;
        }
        if (types > 1) {
            return LineEnding.MIXED;
        }
        return crlf ? LineEnding.CRLF : (lf ? LineEnding.LF : LineEnding.CR);
    }

    private static boolean startsWith(byte[] bytes, int... expected) {
        if (bytes.length < expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (Byte.toUnsignedInt(bytes[index]) != expected[index]) {
                return false;
            }
        }
        return true;
    }

    record Result(EncodingHint encodingHint, LineEnding lineEnding) {}
}
