package io.veridex.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 备份/恢复脚本静态契约（spec §6/§7）：存在、fail-fast、manifest 字段、--fresh 保护。
 */
class BackupScriptStaticContractTest {

    private static final Path BACKUP = Path.of("..", "deploy", "backup");

    private String script(String name) throws Exception {
        Path p = BACKUP.resolve(name);
        assertThat(p).as("%s", name).exists();
        return Files.readString(p);
    }

    @Test
    void backupScriptIsFailFastAndWritesManifest() throws Exception {
        String s = script("backup.sh");
        assertThat(s).contains("set -euo pipefail");
        assertThat(s).contains("pg_dump");
        assertThat(s).contains("manifest.json");
        assertThat(s).contains("shasum -a 256");   // SHA-256 校验和
        assertThat(s).contains("flyway_schema_history"); // 记录迁移版本
        // 失败清理：不留半成品
        assertThat(s).contains("trap");
        assertThat(s).contains("rm -rf");
    }

    @Test
    void restoreScriptGuardsNonFreshAndIncompatibleVersions() throws Exception {
        String s = script("restore.sh");
        assertThat(s).contains("set -euo pipefail");
        assertThat(s).contains("pg_restore");
        // --fresh 二次确认
        assertThat(s).contains("--fresh");
        assertThat(s).contains("YES");
        // 版本兼容校验（目标 schema_version 不得高于备份版本）
        assertThat(s).contains("schema_version");
        // 一次性重建容器
        assertThat(s).contains("reindex");
        assertThat(s).contains("SPRING_PROFILES_ACTIVE=reindex");
    }

    @Test
    void recoveryDrillScriptDrivesFullCycle() throws Exception {
        String s = script("verify-recovery.sh");
        assertThat(s).contains("set -euo pipefail");
        assertThat(s).contains("backup.sh");
        assertThat(s).contains("restore.sh");
        assertThat(s).contains("smoke.sh");
        assertThat(s).contains("--fresh");
        assertThat(s).contains("RTO");
    }
}
