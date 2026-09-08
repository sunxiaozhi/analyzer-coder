package com.analyzercoder.application.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CodeGraphProcessOutputTest {
    @Test
    void preservesUtf8CliOutput() {
        assertThat(CodeGraphProcessOutput.decode("构建完成: 12 nodes".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("构建完成: 12 nodes");
    }

    @Test
    void decodesChineseWindowsConsoleOutput() {
        byte[] output =
                "'codegraph' 不是内部或外部命令，也不是可运行的程序或批处理文件。"
                        .getBytes(Charset.forName("GB18030"));
        String decoded = CodeGraphProcessOutput.decode(output);
        assertThat(decoded).doesNotContain("�").contains("不是内部或外部命令");
        assertThat(CodeGraphProcessOutput.failureMessage(decoded))
                .isEqualTo("未找到 CodeGraph CLI，请安装并配置 app.codegraph.executable");
    }
}
