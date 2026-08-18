package cn.zimu.fulfillment.connector.wecom;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * wecom-message-intake 12：内容寻址媒体存储在「进程重启」后的持久性。
 *
 * <p>在持久化目录（对应 docker-compose 的 app-media-data 命名卷）上新建一个存储实例模拟服务重启，
 * 原图仍能按受控引用读取且哈希稳定。
 */
class WecomMediaFileStoreRestartPersistenceTest {

    @TempDir
    Path directory;

    @Test
    void contentAddressedMediaSurvivesStoreRecreationLikeContainerRestart() {
        byte[] original = "服务重启后仍能从受权接口读取的原图字节".getBytes(StandardCharsets.UTF_8);

        WecomMediaFileStore firstProcess = new WecomMediaFileStore(directory.toString());
        String contentRef = firstProcess.put(original);
        String sha256 = firstProcess.sha256(original);

        // 模拟容器重启：同一持久化卷上全新实例，不共享任何内存状态
        WecomMediaFileStore restartedProcess = new WecomMediaFileStore(directory.toString());
        assertThat(restartedProcess.read(contentRef)).isEqualTo(original);
        assertThat(restartedProcess.sha256(restartedProcess.read(contentRef))).isEqualTo(sha256);

        // 受控引用必须落在持久化目录内（内容寻址语义不变）
        Path normalized = Path.of(contentRef).toAbsolutePath().normalize();
        assertThat(normalized.startsWith(directory.toAbsolutePath().normalize())).isTrue();
    }

    @Test
    void sameContentReusesTheSameFileAcrossStoreInstances() {
        byte[] bytes = "跨实例复用的同一原图".getBytes(StandardCharsets.UTF_8);
        WecomMediaFileStore first = new WecomMediaFileStore(directory.toString());
        String firstRef = first.put(bytes);
        WecomMediaFileStore second = new WecomMediaFileStore(directory.toString());
        String secondRef = second.put(bytes);
        assertThat(secondRef).isEqualTo(firstRef);
    }
}
